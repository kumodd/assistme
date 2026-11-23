package com.personalai.primary.memory.uoai

import android.content.Intent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object UoaiConstants {
    const val PROVIDER_PACKAGE = "com.uoai.provider"
    const val ACTION_REGISTER = "com.uoai.provider.action.REGISTER"
    const val BASE_URL = "http://127.0.0.1:8765/v1"
    const val MODEL_AUTO = "auto"
    const val MAX_REQUEST_BYTES = 2_097_152 // 2 MiB
    const val HARD_MAX_TOKENS = 32_768
    const val GENERATION_TIMEOUT_MS = 300_000L // 300 s
    const val EXTRA_TOKEN = "token"
    const val EXTRA_CLIENT_ID = "client_id"
    const val EXTRA_ERROR_CODE = "error_code"
    const val EXTRA_ERROR = "error"
}

@Serializable
data class UoaiHealthResponse(
    val status: String = "unknown",
    @SerialName("model_state") val modelState: String = "UNKNOWN",
    @SerialName("model_loaded") val modelLoaded: Boolean = false,
    @SerialName("api_version") val apiVersion: String = "v1",
    @SerialName("model_error") val modelError: String? = null
)

@Serializable
data class UoaiModelInfo(
    val id: String = "",
    val `object`: String = "model",
    @SerialName("owned_by") val ownedBy: String = "universal-offline-ai",
    val name: String = "",
    val format: String = "",
    val quantization: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val ready: Boolean = false,
    @SerialName("context_window") val contextWindow: Int = 0
)

@Serializable
data class UoaiModelsResponse(
    val `object`: String = "list",
    val data: List<UoaiModelInfo> = emptyList()
)

@Serializable
data class UoaiCapabilities(
    val features: List<String> = emptyList(),
    @SerialName("max_request_bytes") val maxRequestBytes: Int = UoaiConstants.MAX_REQUEST_BYTES,
    @SerialName("max_tokens_limit") val maxTokensLimit: Int = UoaiConstants.HARD_MAX_TOKENS,
    @SerialName("concurrent_generations") val concurrentGenerations: Int = 1,
    val embeddings: Boolean = false,
    @SerialName("provider_tools") val providerTools: List<String> = emptyList(),
    @SerialName("context_window_tokens") val contextWindowTokens: Int = 8_192,
    @SerialName("default_max_tokens") val defaultMaxTokens: Int = 2_048
) {
    val supportsStreaming: Boolean get() = "streaming" in features
    val supportsCancellation: Boolean get() = "cancellation" in features
    val supportsToolCalling: Boolean get() = "tool-calling" in features
}

@Serializable
data class UoaiChatMessage(
    val role: String,
    val content: String,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class UoaiChatCompletionRequest(
    val model: String = UoaiConstants.MODEL_AUTO,
    val messages: List<UoaiChatMessage>,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    val stream: Boolean = false,
    @SerialName("use_provider_tools") val useProviderTools: Boolean? = null
)

@Serializable
data class UoaiToolCallFunction(
    val name: String = "",
    val arguments: String = ""
)

@Serializable
data class UoaiToolCall(
    val id: String = "",
    val type: String = "function",
    val function: UoaiToolCallFunction = UoaiToolCallFunction()
)

@Serializable
data class UoaiChoiceMessage(
    val role: String = "assistant",
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<UoaiToolCall>? = null
)

@Serializable
data class UoaiChoice(
    val index: Int = 0,
    val message: UoaiChoiceMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class UoaiChatCompletionResponse(
    val id: String = "",
    val `object`: String = "chat.completion",
    val created: Long = 0L,
    val model: String = "",
    val choices: List<UoaiChoice> = emptyList()
)

@Serializable
data class UoaiStreamDelta(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class UoaiStreamChoice(
    val index: Int = 0,
    val delta: UoaiStreamDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class UoaiStreamChunk(
    val id: String = "",
    val model: String = "",
    val choices: List<UoaiStreamChoice> = emptyList(),
    val error: UoaiErrorBody? = null
)

@Serializable
data class UoaiErrorBody(
    val message: String = "Unknown UOAI error",
    val type: String = "api_error",
    val code: String? = null
)

@Serializable
data class UoaiErrorEnvelope(
    val error: UoaiErrorBody? = null
)

@Serializable
data class UoaiCancelRequest(
    @SerialName("request_id") val requestId: String
)

@Serializable
data class UoaiCancelResponse(
    @SerialName("request_id") val requestId: String = "",
    val cancelled: Boolean = false
)

sealed interface UoaiConnectionState {
    data object ProviderNotInstalled : UoaiConnectionState
    data class NeedsRegistration(val registrationIntent: Intent) : UoaiConnectionState
    data class ProviderNotRunning(val launchProviderIntent: Intent?) : UoaiConnectionState
    data class ModelNotReady(
        val modelState: String,
        val modelError: String?,
        val launchProviderIntent: Intent?
    ) : UoaiConnectionState
    data class Connected(
        val activeModel: UoaiModelInfo?,
        val capabilities: UoaiCapabilities
    ) : UoaiConnectionState
    data class Error(
        val message: String,
        val errorCode: String? = null,
        val launchProviderIntent: Intent? = null
    ) : UoaiConnectionState
}

sealed interface UoaiRegistrationResult {
    data class Approved(val clientId: String?) : UoaiRegistrationResult
    data class Denied(val errorCode: String, val message: String) : UoaiRegistrationResult
}

sealed class UoaiException(
    message: String,
    val errorCode: String? = null,
    val httpStatus: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause) {
    class ProviderNotInstalled : UoaiException("Universal Offline AI Provider (com.uoai.provider) is not installed.")
    class ProviderNotRunning(cause: Throwable? = null) :
        UoaiException("Universal Offline AI Provider service is not running. Open UOAI Provider and start the API.", cause = cause)
    class NotRegistered(message: String = "UOAI registration required or token revoked.", code: String? = "invalid_token") :
        UoaiException(message, errorCode = code, httpStatus = 401)
    class PermissionDenied(message: String, code: String? = "permission_denied") :
        UoaiException(message, errorCode = code, httpStatus = 403)
    class ModelNotReady(message: String, code: String? = "model_not_ready") :
        UoaiException(message, errorCode = code, httpStatus = 503)
    class RequestCancelled(message: String = "Generation cancelled.") :
        UoaiException(message, errorCode = "request_cancelled", httpStatus = 409)
    class ApiError(message: String, code: String? = null, status: Int? = null) :
        UoaiException(message, errorCode = code, httpStatus = status)
}

sealed interface UoaiSseEvent {
    data class Token(val text: String) : UoaiSseEvent
    data object Done : UoaiSseEvent
    data object Cancelled : UoaiSseEvent
    data class StreamError(val code: String?, val message: String) : UoaiSseEvent
    data object Ignore : UoaiSseEvent
}

/**
 * Pure JVM helper for UOAI SSE event parsing, dynamic context budgeting, and robust JSON object extraction.
 */
object UoaiProtocolHelpers {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseSseLine(rawLine: String): UoaiSseEvent {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
            return UoaiSseEvent.Ignore
        }
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return UoaiSseEvent.Ignore
        if (payload == "[DONE]") return UoaiSseEvent.Done

        // Detect mid-stream error object cleanly
        val obj = runCatching { json.decodeFromString<JsonObject>(payload) }.getOrNull()
        if (obj != null && obj.containsKey("error")) {
            val env = runCatching { json.decodeFromString<UoaiErrorEnvelope>(payload) }.getOrNull()
            return UoaiSseEvent.StreamError(
                code = env?.error?.code,
                message = env?.error?.message ?: "Stream error from UOAI provider"
            )
        }

        val chunk = runCatching { json.decodeFromString<UoaiStreamChunk>(payload) }.getOrNull()
            ?: return UoaiSseEvent.Ignore
        if (chunk.error != null) {
            return UoaiSseEvent.StreamError(chunk.error.code, chunk.error.message)
        }
        val choice = chunk.choices.firstOrNull() ?: return UoaiSseEvent.Ignore
        if (choice.finishReason == "cancelled") {
            return UoaiSseEvent.Cancelled
        }
        val deltaText = choice.delta?.content
        return if (!deltaText.isNullOrEmpty()) {
            UoaiSseEvent.Token(deltaText)
        } else {
            UoaiSseEvent.Ignore
        }
    }

    /**
     * Budgets output max_tokens and input character cap dynamically against GET /v1/capabilities
     * so prompt_tokens + max_tokens stays comfortably inside context_window_tokens (up to 32K).
     */
    fun computeEffectiveMaxTokens(
        requestedMaxTokens: Int,
        capabilities: UoaiCapabilities?,
        estimatedPromptTokens: Int
    ): Int {
        val capWindow = (capabilities?.contextWindowTokens ?: 8_192).coerceAtLeast(1_024)
        val capLimit = minOf(capabilities?.maxTokensLimit ?: UoaiConstants.HARD_MAX_TOKENS, UoaiConstants.HARD_MAX_TOKENS)
        val availableForOutput = (capWindow - estimatedPromptTokens - 128).coerceAtLeast(128)
        return minOf(requestedMaxTokens, capLimit, availableForOutput).coerceIn(64, UoaiConstants.HARD_MAX_TOKENS)
    }

    fun estimateTokenCount(text: String): Int = (text.length / 3).coerceAtLeast(1)

    fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        val unfenced = if (trimmed.contains("```")) {
            val startFence = trimmed.indexOf("```")
            val afterOpening = trimmed.indexOf('\n', startFence).let { if (it >= 0) it + 1 else startFence + 3 }
            val endFence = trimmed.lastIndexOf("```")
            if (endFence > afterOpening) trimmed.substring(afterOpening, endFence).trim() else trimmed
        } else {
            trimmed
        }
        val firstBrace = unfenced.indexOf('{')
        val lastBrace = unfenced.lastIndexOf('}')
        return if (firstBrace >= 0 && lastBrace > firstBrace) {
            unfenced.substring(firstBrace, lastBrace + 1)
        } else {
            unfenced
        }
    }
}
