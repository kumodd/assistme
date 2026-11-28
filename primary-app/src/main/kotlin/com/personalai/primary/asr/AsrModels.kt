package com.personalai.primary.asr

import com.personalai.primary.memory.uoai.UoaiConstants

enum class AsrProviderType(val displayName: String) {
    UOAI_PROVIDER("UOAI Provider (Gemma 4 / Whisper / Cactus)"),
    ANDROID_ON_DEVICE("On-Device Speech (Android 13+ Offline)"),
    LOCAL_WHISPER_SERVER("Local Loopback Whisper (127.0.0.1)")
}

enum class AsrLanguageMode(val displayName: String, val isoHint: String?) {
    AUTO("Auto Detect", null),
    HINGLISH("Hinglish (EN + HI)", "en,hi"),
    ENGLISH("English", "en"),
    HINDI("Hindi", "hi")
}

enum class UoaiAsrScript(val displayName: String, val apiValue: String) {
    CANONICAL("Canonical", "canonical"),
    ROMAN("Roman Hinglish", "roman"),
    DEVANAGARI("Devanagari", "devanagari"),
    BOTH("Both Scripts", "both")
}

data class AsrRequest(
    val segmentId: String,
    val wavBytes: ByteArray,
    val languageMode: AsrLanguageMode,
    val localBaseUrl: String = "http://127.0.0.1:8080",
    val localModel: String = "whisper-local",
    val uoaiProviderId: String = UoaiConstants.AUDIO_PROVIDER_GEMMA4,
    val uoaiModel: String = "",
    val uoaiScript: UoaiAsrScript = UoaiAsrScript.CANONICAL,
    val uoaiPrompt: String? = null
)

data class AsrResult(
    val text: String,
    val detectedLanguage: String,
    val confidence: Float,
    val providerName: String,
    val speakerLabel: String? = null
)

interface AsrProvider {
    val providerType: AsrProviderType
    suspend fun transcribe(request: AsrRequest): Result<AsrResult>
}
