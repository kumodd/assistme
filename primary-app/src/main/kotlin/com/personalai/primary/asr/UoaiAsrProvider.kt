package com.personalai.primary.asr

import android.util.Log
import com.personalai.primary.memory.uoai.UoaiAudioTranscriptionParams
import com.personalai.primary.memory.uoai.UoaiAudioTranscriptionResponse
import com.personalai.primary.memory.uoai.UoaiClient
import com.personalai.primary.memory.uoai.UoaiConstants
import com.personalai.primary.memory.uoai.UoaiException
import com.personalai.primary.memory.uoai.UoaiProtocolHelpers
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Speech-to-Text provider powered by Universal Offline AI Provider (`com.uoai.provider`,
 * `http://127.0.0.1:8765/v1/audio/transcriptions`).
 *
 * Supports:
 * 1. Direct On-Device Gemma 4 Multimodal (`provider=gemma4`) — preferred default
 * 2. Dedicated On-Device Whisper (`provider=whisper`)
 * 3. Lightweight On-Device Hinglish Cactus (`provider=cactus`, `akshar-v4` / `shruti-hi`)
 * 4. Any external inference provider configured inside UOAI Provider (`provider=<id>`)
 */
@Singleton
class UoaiAsrProvider @Inject constructor(
    private val uoaiClient: UoaiClient
) : AsrProvider {

    class TransientUoaiAsrException(message: String, cause: Throwable? = null) : IOException(message, cause)

    override val providerType: AsrProviderType = AsrProviderType.UOAI_PROVIDER

    override suspend fun transcribe(request: AsrRequest): Result<AsrResult> {
        val params = buildParams(request)
        return try {
            val response = uoaiClient.transcribeAudio(
                wavBytes = request.wavBytes,
                fileName = "${request.segmentId}.wav",
                params = params
            )
            val cleanText = extractTranscriptText(response, request.uoaiScript)
            val detectedLang = LanguageDetector.normalizeAndRefine(response.language, cleanText)
            val confidence = computeConfidence(response, params.provider)
            val providerLabel = formatProviderLabel(response, params.provider)
            Log.d(
                TAG,
                "UOAI ASR ok seg=${request.segmentId} provider=$providerLabel lang=$detectedLang chars=${cleanText.length} elapsed=${response.elapsedMs ?: -1}ms"
            )
            Result.success(
                AsrResult(
                    text = cleanText,
                    detectedLanguage = detectedLang,
                    confidence = confidence,
                    providerName = providerLabel
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: UoaiException.TransientBusy) {
            Result.failure(TransientUoaiAsrException(formatActionableError(e), e))
        } catch (e: UoaiException) {
            Result.failure(IllegalStateException(formatActionableError(e), e))
        } catch (e: Throwable) {
            Result.failure(IllegalStateException("UOAI transcription error: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    companion object {
        private const val TAG = "UoaiAsrProvider"

        fun buildParams(request: AsrRequest): UoaiAudioTranscriptionParams {
            val providerId = request.uoaiProviderId.trim().ifEmpty { UoaiConstants.AUDIO_PROVIDER_GEMMA4 }
            val resolvedModel = UoaiProtocolHelpers.resolveDefaultAudioModel(providerId, request.uoaiModel)
            val languageParam = when (request.languageMode) {
                AsrLanguageMode.AUTO -> "auto"
                AsrLanguageMode.HINGLISH -> "en,hi"
                AsrLanguageMode.ENGLISH -> "en"
                AsrLanguageMode.HINDI -> "hi"
            }
            return UoaiAudioTranscriptionParams(
                provider = providerId,
                model = resolvedModel,
                language = languageParam,
                prompt = request.uoaiPrompt?.trim()?.takeIf { it.isNotEmpty() },
                script = request.uoaiScript.apiValue,
                responseFormat = "verbose_json",
                temperature = 0.0f,
                translate = false,
                timestamps = true
            )
        }

        fun extractTranscriptText(
            response: UoaiAudioTranscriptionResponse,
            requestedScript: UoaiAsrScript
        ): String {
            val primary = response.text.trim()
            val roman = response.romanHinglish?.trim().orEmpty()
            val devanagari = response.devanagariHinglish?.trim().orEmpty()

            return when (requestedScript) {
                UoaiAsrScript.ROMAN -> {
                    when {
                        roman.isNotEmpty() && (primary.isEmpty() || containsDevanagari(primary)) -> roman
                        primary.isNotEmpty() -> primary
                        else -> joinSegments(response)
                    }
                }
                UoaiAsrScript.DEVANAGARI -> {
                    when {
                        primary.isNotEmpty() -> primary
                        devanagari.isNotEmpty() -> devanagari
                        else -> joinSegments(response)
                    }
                }
                UoaiAsrScript.BOTH -> {
                    when {
                        primary.isNotEmpty() -> primary
                        devanagari.isNotEmpty() && roman.isNotEmpty() -> "$devanagari\n$roman"
                        devanagari.isNotEmpty() -> devanagari
                        roman.isNotEmpty() -> roman
                        else -> joinSegments(response)
                    }
                }
                UoaiAsrScript.CANONICAL -> {
                    primary.ifEmpty {
                        joinSegments(response).ifEmpty { roman.ifEmpty { devanagari } }
                    }
                }
            }.trim()
        }

        fun computeConfidence(
            response: UoaiAudioTranscriptionResponse,
            requestedProviderId: String
        ): Float {
            val logProbs = response.segments.mapNotNull { it.avgLogProb }
            if (logProbs.isNotEmpty()) {
                val base = exp(logProbs.average()).toFloat().coerceIn(0.05f, 1.0f)
                val noSpeechAvg = response.segments.mapNotNull { it.noSpeechProb }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat()
                return if (noSpeechAvg != null && noSpeechAvg > 0.75f) {
                    (base * (1f - (noSpeechAvg - 0.5f))).coerceIn(0.10f, 1.0f)
                } else {
                    base
                }
            }
            val effectiveProvider = (response.provider?.takeIf { it.isNotBlank() } ?: requestedProviderId).lowercase()
            return when (effectiveProvider) {
                UoaiConstants.AUDIO_PROVIDER_GEMMA4 -> 0.92f
                UoaiConstants.AUDIO_PROVIDER_WHISPER -> 0.88f
                UoaiConstants.AUDIO_PROVIDER_CACTUS -> 0.86f
                else -> 0.90f
            }
        }

        fun formatProviderLabel(
            response: UoaiAudioTranscriptionResponse,
            requestedProviderId: String
        ): String {
            val prov = (response.provider?.trim()?.takeIf { it.isNotEmpty() } ?: requestedProviderId).trim()
            val model = response.model.trim()
            val prettyProv = when (prov.lowercase()) {
                UoaiConstants.AUDIO_PROVIDER_GEMMA4 -> "UOAI Gemma 4"
                UoaiConstants.AUDIO_PROVIDER_WHISPER -> "UOAI Whisper"
                UoaiConstants.AUDIO_PROVIDER_CACTUS -> "UOAI Cactus"
                UoaiConstants.AUDIO_PROVIDER_AUTO, "" -> "UOAI Auto"
                else -> "UOAI ($prov)"
            }
            return if (model.isNotEmpty()) "$prettyProv • $model" else prettyProv
        }

        fun formatActionableError(err: UoaiException): String {
            return when (err) {
                is UoaiException.ProviderNotInstalled ->
                    "UOAI Provider (com.uoai.provider) is not installed. Install it or switch ASR engine in Settings."
                is UoaiException.ProviderNotRunning ->
                    "UOAI Provider is not running. Open UOAI Provider and start the local API, then tap Retry."
                is UoaiException.NotRegistered ->
                    "UOAI approval required (missing/revoked token). Open Settings → Approve UOAI Access, then retry."
                is UoaiException.PermissionDenied ->
                    "Missing 'speech_to_text' permission in UOAI Provider → Clients. Enable Speech-to-Text for Sunbo, then retry."
                else -> when (err.errorCode) {
                    "gemma4_audio_unsupported" ->
                        "Active Gemma model has no audio encoder. Load Gemma 4 E2B or E4B (.litertlm) in UOAI Provider, or switch ASR engine to Whisper/Cactus."
                    "asr_model_not_ready", "model_not_ready" ->
                        "Selected UOAI audio model is not loaded yet. Open UOAI Provider or tap Load Model in Settings."
                    "provider_disabled" ->
                        "Selected UOAI audio provider is disabled. Enable it in UOAI Provider or pick another engine."
                    "provider_not_found", "model_not_found" ->
                        "Requested UOAI audio provider/model was not found or is not downloaded in UOAI Provider."
                    "audio_too_large" ->
                        "Audio chunk exceeds UOAI 25 MiB upload limit."
                    "audio_too_long" ->
                        "Audio chunk exceeds UOAI 10-minute duration limit."
                    "insufficient_memory" ->
                        "Not enough free RAM for this UOAI audio model. Try Cactus (Akshar) or Whisper tiny."
                    "thermal_throttled" ->
                        "Device is thermally throttled; UOAI temporarily paused audio inference."
                    "asr_busy", "queue_full" ->
                        "UOAI transcription engine is momentarily busy."
                    "model_mismatch" ->
                        "UOAI Whisper profile mismatch — unload/switch active Whisper profile in UOAI Provider or use Auto."
                    "request_timeout", "external_provider_timeout" ->
                        "UOAI audio transcription timed out."
                    "external_provider_unreachable" ->
                        "Configured external UOAI audio provider is unreachable."
                    else -> err.message ?: "UOAI transcription failed (${err.errorCode ?: err.httpStatus ?: "error"})"
                }
            }
        }

        private fun joinSegments(response: UoaiAudioTranscriptionResponse): String {
            return response.segments
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }

        private fun containsDevanagari(text: String): Boolean {
            return text.any { it in '\u0900'..'\u097F' }
        }
    }
}
