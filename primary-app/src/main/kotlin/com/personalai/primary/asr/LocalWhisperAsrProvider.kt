package com.personalai.primary.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

@Serializable
private data class WhisperVerboseResponse(
    val text: String = "",
    val language: String? = null,
    val segments: List<WhisperSegmentInfo> = emptyList()
)

@Serializable
private data class WhisperSegmentInfo(
    @SerialName("avg_logprob") val avgLogProb: Float? = null,
    @SerialName("no_speech_prob") val noSpeechProb: Float? = null
)

/**
 * 100% privacy-focused loopback-only Whisper ASR client (`127.0.0.1` / `localhost`).
 * Strictly rejects any non-loopback or remote cloud host so audio never leaves this device.
 */
@Singleton
class LocalWhisperAsrProvider @Inject constructor(
    private val httpClient: OkHttpClient
) : AsrProvider {

    override val providerType: AsrProviderType = AsrProviderType.LOCAL_WHISPER_SERVER
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribe(request: AsrRequest): Result<AsrResult> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = validateLoopbackEndpoint(request.localBaseUrl)
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "${request.segmentId}.wav",
                    request.wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", request.localModel.ifBlank { "whisper-local" })
                .addFormDataPart("response_format", "verbose_json")

            request.languageMode.isoHint
                ?.takeIf { !it.contains(',') }
                ?.let { lang ->
                    multipartBuilder.addFormDataPart("language", lang)
                }

            val req = Request.Builder()
                .url(endpoint)
                .post(multipartBuilder.build())
                .build()

            httpClient.newCall(req).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Local Whisper HTTP ${response.code}: ${bodyText.take(240)}")
                }
                val parsed = json.decodeFromString<WhisperVerboseResponse>(bodyText)
                val cleanText = parsed.text.trim()
                val detectedLang = LanguageDetector.normalizeAndRefine(parsed.language, cleanText)
                val confidence = computeConfidence(parsed.segments)

                AsrResult(
                    text = cleanText,
                    detectedLanguage = detectedLang,
                    confidence = confidence,
                    providerName = "Local Loopback Whisper"
                )
            }
        }
    }

    private fun computeConfidence(segments: List<WhisperSegmentInfo>): Float {
        val logProbs = segments.mapNotNull { it.avgLogProb }
        if (logProbs.isEmpty()) return 0.85f
        val avg = logProbs.average()
        return exp(avg).toFloat().coerceIn(0.05f, 1.0f)
    }

    companion object {
        private val ALLOWED_LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

        fun validateLoopbackEndpoint(rawBaseUrl: String): String {
            val base = rawBaseUrl.trim().trimEnd('/')
            require(base.isNotEmpty()) { "Local Whisper server URL is empty." }
            val endpoint = if (base.endsWith("/audio/transcriptions")) {
                base
            } else {
                "$base/v1/audio/transcriptions"
            }
            val parsed = endpoint.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid Local Whisper URL: $rawBaseUrl")
            if (parsed.host.lowercase() !in ALLOWED_LOOPBACK_HOSTS) {
                throw SecurityException(
                    "Privacy guard blocked non-loopback ASR host '${parsed.host}'. Only 127.0.0.1 / localhost is permitted."
                )
            }
            return endpoint
        }
    }
}
