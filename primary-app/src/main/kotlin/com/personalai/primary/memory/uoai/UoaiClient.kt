package com.personalai.primary.memory.uoai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.personalai.primary.security.PrimaryCredentialsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Path B Direct HTTP/SSE client for Universal Offline AI Provider (`com.uoai.provider`).
 * Communicates strictly over localhost loopback (`http://127.0.0.1:8765/v1`) with OS-verified
 * one-time registration and Android Keystore AES-256-GCM encrypted Bearer token storage.
 */
@Singleton
class UoaiClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialsStore: PrimaryCredentialsStore
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // Dedicated OkHttpClient with 300s read timeout matching UOAI Provider generation timeout.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(UoaiConstants.GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _connectionState = MutableStateFlow<UoaiConnectionState?>(null)
    val connectionState: StateFlow<UoaiConnectionState?> = _connectionState.asStateFlow()

    @Volatile
    var cachedCapabilities: UoaiCapabilities? = null
        private set

    @Volatile
    var cachedActiveModel: UoaiModelInfo? = null
        private set

    fun isProviderInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    UoaiConstants.PROVIDER_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(UoaiConstants.PROVIDER_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Launch with `ActivityResultContracts.StartActivityForResult()`.
     * NEVER add `FLAG_ACTIVITY_NEW_TASK` or Android clears OS caller identity.
     */
    fun registrationIntent(): Intent {
        return Intent(UoaiConstants.ACTION_REGISTER).setPackage(UoaiConstants.PROVIDER_PACKAGE)
    }

    fun openProviderIntent(): Intent? {
        return context.packageManager
            .getLaunchIntentForPackage(UoaiConstants.PROVIDER_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun handleRegistrationResult(resultCode: Int, data: Intent?): UoaiRegistrationResult {
        return if (resultCode == Activity.RESULT_OK) {
            val token = data?.getStringExtra(UoaiConstants.EXTRA_TOKEN)?.trim()
            val clientId = data?.getStringExtra(UoaiConstants.EXTRA_CLIENT_ID)?.trim()
            if (token.isNullOrBlank()) {
                UoaiRegistrationResult.Denied("missing_token", "Provider returned RESULT_OK without a token.")
            } else {
                credentialsStore.saveUoaiCredentials(token, clientId)
                Log.i(TAG, "UOAI provider registration approved (clientId=${clientId ?: "-"})")
                UoaiRegistrationResult.Approved(clientId)
            }
        } else {
            val code = data?.getStringExtra(UoaiConstants.EXTRA_ERROR_CODE) ?: "cancelled"
            val msg = data?.getStringExtra(UoaiConstants.EXTRA_ERROR)
                ?: "Registration cancelled or denied by user."
            Log.w(TAG, "UOAI provider registration denied: code=$code msg=$msg")
            UoaiRegistrationResult.Denied(code, msg)
        }
    }

    fun clearCredentials() {
        credentialsStore.clearUoaiCredentials()
        cachedCapabilities = null
        cachedActiveModel = null
    }

    suspend fun checkConnection(): UoaiConnectionState = withContext(Dispatchers.IO) {
        val state = evaluateConnection()
        _connectionState.value = state
        state
    }

    private fun evaluateConnection(): UoaiConnectionState {
        if (!isProviderInstalled()) {
            return UoaiConnectionState.ProviderNotInstalled
        }

        val token = credentialsStore.getUoaiToken()
        if (token.isNullOrBlank()) {
            return UoaiConnectionState.NeedsRegistration(registrationIntent())
        }

        // 1. Unauthenticated health check
        val health = try {
            fetchHealth()
        } catch (_: ConnectException) {
            return UoaiConnectionState.ProviderNotRunning(openProviderIntent())
        } catch (e: IOException) {
            return UoaiConnectionState.ProviderNotRunning(openProviderIntent())
        } catch (e: Throwable) {
            return UoaiConnectionState.Error(e.message ?: "Health check failed", launchProviderIntent = openProviderIntent())
        }

        if (!health.modelLoaded) {
            return UoaiConnectionState.ModelNotReady(
                modelState = health.modelState,
                modelError = health.modelError,
                launchProviderIntent = openProviderIntent()
            )
        }

        // 2. Authenticated capabilities + active model inspection
        return try {
            val caps = fetchCapabilities(token)
            val models = fetchModels(token)
            val activeModel = models.firstOrNull { it.ready } ?: models.firstOrNull()
            cachedCapabilities = caps
            cachedActiveModel = activeModel
            UoaiConnectionState.Connected(activeModel = activeModel, capabilities = caps)
        } catch (e: UoaiException.NotRegistered) {
            // Automatically clear stale/revoked token per developer guide.
            clearCredentials()
            UoaiConnectionState.NeedsRegistration(registrationIntent())
        } catch (e: UoaiException.PermissionDenied) {
            UoaiConnectionState.Error(
                message = e.message ?: "Permission denied in UOAI Provider → Clients",
                errorCode = e.errorCode,
                launchProviderIntent = openProviderIntent()
            )
        } catch (e: UoaiException.ProviderNotRunning) {
            UoaiConnectionState.ProviderNotRunning(openProviderIntent())
        } catch (e: UoaiException) {
            UoaiConnectionState.Error(
                message = e.message ?: "UOAI check failed",
                errorCode = e.errorCode,
                launchProviderIntent = openProviderIntent()
            )
        }
    }

    suspend fun chatCompletion(
        messages: List<UoaiChatMessage>,
        temperature: Float = 0.1f,
        requestedMaxTokens: Int = 1_024,
        useProviderTools: Boolean? = null
    ): String = withContext(Dispatchers.IO) {
        val token = requireReadyToken()
        ensureCapabilitiesLoaded(token)

        val promptTokens = messages.sumOf { UoaiProtocolHelpers.estimateTokenCount(it.content) }
        val effectiveMaxTokens = UoaiProtocolHelpers.computeEffectiveMaxTokens(
            requestedMaxTokens = requestedMaxTokens,
            capabilities = cachedCapabilities,
            estimatedPromptTokens = promptTokens
        )

        val reqObj = UoaiChatCompletionRequest(
            model = UoaiConstants.MODEL_AUTO,
            messages = messages,
            temperature = temperature.coerceIn(0f, 2f),
            maxTokens = effectiveMaxTokens,
            stream = false,
            useProviderTools = useProviderTools
        )
        val bodyBytes = json.encodeToString(reqObj).toByteArray(Charsets.UTF_8)
        val maxReqBytes = cachedCapabilities?.maxRequestBytes ?: UoaiConstants.MAX_REQUEST_BYTES
        if (bodyBytes.size > maxReqBytes) {
            throw UoaiException.ApiError(
                "Request size (${bodyBytes.size}B) exceeds UOAI limit ($maxReqBytes B).",
                code = "request_too_large",
                status = 413
            )
        }

        // Single brief backoff retry if provider queue is momentarily full (HTTP 429)
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                return@withContext executeChatCompletionOnce(token, bodyBytes)
            } catch (e: UoaiException.ApiError) {
                lastError = e
                if (e.httpStatus == 429 && attempt == 0) {
                    delay(1_500L)
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: UoaiException.ApiError("UOAI completion failed")
    }

    fun chatCompletionStream(
        messages: List<UoaiChatMessage>,
        temperature: Float = 0.2f,
        requestedMaxTokens: Int = 1_536
    ): Flow<String> = flow {
        val token = requireReadyToken()
        ensureCapabilitiesLoaded(token)

        val promptTokens = messages.sumOf { UoaiProtocolHelpers.estimateTokenCount(it.content) }
        val effectiveMaxTokens = UoaiProtocolHelpers.computeEffectiveMaxTokens(
            requestedMaxTokens = requestedMaxTokens,
            capabilities = cachedCapabilities,
            estimatedPromptTokens = promptTokens
        )

        val reqObj = UoaiChatCompletionRequest(
            model = UoaiConstants.MODEL_AUTO,
            messages = messages,
            temperature = temperature.coerceIn(0f, 2f),
            maxTokens = effectiveMaxTokens,
            stream = true
        )
        val bodyBytes = json.encodeToString(reqObj).toByteArray(Charsets.UTF_8)
        val maxReqBytes = cachedCapabilities?.maxRequestBytes ?: UoaiConstants.MAX_REQUEST_BYTES
        if (bodyBytes.size > maxReqBytes) {
            throw UoaiException.ApiError("Request exceeds UOAI 2 MiB limit", "request_too_large", 413)
        }

        val request = Request.Builder()
            .url("${UoaiConstants.BASE_URL}/chat/completions")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(bodyBytes.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        var requestId: String? = null
        try {
            val response = try {
                call.execute()
            } catch (e: ConnectException) {
                _connectionState.value = UoaiConnectionState.ProviderNotRunning(openProviderIntent())
                throw UoaiException.ProviderNotRunning(e)
            } catch (e: SocketTimeoutException) {
                throw UoaiException.ApiError("UOAI generation timed out (300s).", "request_timeout", 504)
            }

            response.use { resp ->
                requestId = resp.header("X-Request-Id")
                if (!resp.isSuccessful) {
                    val errText = resp.body?.string().orEmpty()
                    throw mapHttpError(resp.code, errText)
                }
                val source = resp.body?.charStream()?.buffered()
                    ?: throw UoaiException.ApiError("Empty SSE stream from UOAI provider")

                while (true) {
                    val line = source.readLine() ?: break
                    when (val ev = UoaiProtocolHelpers.parseSseLine(line)) {
                        is UoaiSseEvent.Token -> emit(ev.text)
                        UoaiSseEvent.Done -> break
                        UoaiSseEvent.Cancelled -> throw UoaiException.RequestCancelled()
                        is UoaiSseEvent.StreamError -> {
                            throw UoaiException.ApiError(ev.message, ev.code)
                        }
                        UoaiSseEvent.Ignore -> Unit
                    }
                }
            }
        } catch (ce: CancellationException) {
            call.cancel()
            val rid = requestId
            if (!rid.isNullOrBlank()) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { cancelRequestInternal(token, rid) }
                }
            }
            throw ce
        }
    }.flowOn(Dispatchers.IO)

    suspend fun cancelRequest(requestId: String): Boolean = withContext(Dispatchers.IO) {
        val token = credentialsStore.getUoaiToken() ?: return@withContext false
        cancelRequestInternal(token, requestId)
    }

    private fun cancelRequestInternal(token: String, requestId: String): Boolean {
        return runCatching {
            val payload = json.encodeToString(UoaiCancelRequest(requestId))
            val req = Request.Builder()
                .url("${UoaiConstants.BASE_URL}/cancel")
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<UoaiCancelResponse>(body).cancelled
            }
        }.getOrDefault(false)
    }

    private fun executeChatCompletionOnce(token: String, bodyBytes: ByteArray): String {
        val request = Request.Builder()
            .url("${UoaiConstants.BASE_URL}/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(bodyBytes.toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: ConnectException) {
            _connectionState.value = UoaiConnectionState.ProviderNotRunning(openProviderIntent())
            throw UoaiException.ProviderNotRunning(e)
        } catch (e: SocketTimeoutException) {
            throw UoaiException.ApiError("UOAI generation timed out (300s).", "request_timeout", 504)
        }

        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw mapHttpError(resp.code, raw)
            }
            val parsed = json.decodeFromString<UoaiChatCompletionResponse>(raw)
            val content = parsed.choices.firstOrNull()?.message?.content?.trim()
            if (content.isNullOrEmpty()) {
                throw UoaiException.ApiError("UOAI returned empty completion content.")
            }
            return content
        }
    }

    private fun requireReadyToken(): String {
        if (!isProviderInstalled()) {
            _connectionState.value = UoaiConnectionState.ProviderNotInstalled
            throw UoaiException.ProviderNotInstalled()
        }
        val token = credentialsStore.getUoaiToken()
        if (token.isNullOrBlank()) {
            _connectionState.value = UoaiConnectionState.NeedsRegistration(registrationIntent())
            throw UoaiException.NotRegistered("Approve UOAI Provider access in Settings first.")
        }
        return token
    }

    private fun ensureCapabilitiesLoaded(token: String) {
        if (cachedCapabilities == null) {
            runCatching {
                cachedCapabilities = fetchCapabilities(token)
                cachedActiveModel = fetchModels(token).firstOrNull { it.ready }
            }
        }
    }

    private fun fetchHealth(): UoaiHealthResponse {
        val req = Request.Builder().url("${UoaiConstants.BASE_URL}/health").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && body.isBlank()) {
                throw UoaiException.ApiError("Health HTTP ${resp.code}", status = resp.code)
            }
            return json.decodeFromString(body)
        }
    }

    private fun fetchCapabilities(token: String): UoaiCapabilities {
        val req = Request.Builder()
            .url("${UoaiConstants.BASE_URL}/capabilities")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: ConnectException) {
            throw UoaiException.ProviderNotRunning(e)
        }
        resp.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw mapHttpError(it.code, body)
            return json.decodeFromString(body)
        }
    }

    private fun fetchModels(token: String): List<UoaiModelInfo> {
        val req = Request.Builder()
            .url("${UoaiConstants.BASE_URL}/models")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: ConnectException) {
            throw UoaiException.ProviderNotRunning(e)
        }
        resp.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw mapHttpError(it.code, body)
            return json.decodeFromString<UoaiModelsResponse>(body).data
        }
    }

    private fun mapHttpError(httpCode: Int, rawBody: String): UoaiException {
        val parsed = runCatching { json.decodeFromString<UoaiErrorEnvelope>(rawBody) }.getOrNull()?.error
        val code = parsed?.code
        val msg = parsed?.message ?: "UOAI HTTP $httpCode: ${rawBody.take(200)}"
        return when (httpCode) {
            401 -> {
                clearCredentials()
                _connectionState.value = UoaiConnectionState.NeedsRegistration(registrationIntent())
                UoaiException.NotRegistered(msg, code)
            }
            403 -> UoaiException.PermissionDenied(msg, code)
            409 -> UoaiException.RequestCancelled(msg)
            503 -> {
                _connectionState.value = UoaiConnectionState.ModelNotReady("NOT_READY", msg, openProviderIntent())
                UoaiException.ModelNotReady(msg, code)
            }
            else -> UoaiException.ApiError(msg, code, httpCode)
        }
    }

    companion object {
        private const val TAG = "UoaiClient"
    }
}
