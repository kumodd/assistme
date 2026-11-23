package com.personalai.primary.asr

enum class AsrProviderType(val displayName: String) {
    ANDROID_ON_DEVICE("On-Device Speech (Android 13+ Offline)"),
    LOCAL_WHISPER_SERVER("Local Loopback Whisper (127.0.0.1)")
}

enum class AsrLanguageMode(val displayName: String, val isoHint: String?) {
    AUTO("Auto (English + Hindi)", null),
    ENGLISH("English", "en"),
    HINDI("Hindi", "hi")
}

data class AsrRequest(
    val segmentId: String,
    val wavBytes: ByteArray,
    val languageMode: AsrLanguageMode,
    val localBaseUrl: String = "http://127.0.0.1:8080",
    val localModel: String = "whisper-local"
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
