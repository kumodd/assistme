package com.personalai.primary.asr

object LanguageDetector {

    fun normalizeAndRefine(providerLanguage: String?, transcriptText: String): String {
        val raw = providerLanguage?.trim()?.lowercase()
        val normalizedProvider = when {
            raw == null || raw.isEmpty() -> null
            raw == "english" || raw.startsWith("en") -> "en"
            raw == "hindi" || raw.startsWith("hi") -> "hi"
            else -> raw.take(8)
        }

        val scriptGuess = detectScriptLanguage(transcriptText)
        return when {
            scriptGuess == "hi-en" -> "hi-en"
            scriptGuess == "hi" -> "hi"
            normalizedProvider != null -> normalizedProvider
            else -> scriptGuess
        }
    }

    fun detectScriptLanguage(text: String): String {
        var devanagariCount = 0
        var latinCount = 0
        for (ch in text) {
            when {
                ch in '\u0900'..'\u097F' -> devanagariCount++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latinCount++
            }
        }
        return when {
            devanagariCount > 0 && latinCount >= 4 -> "hi-en"
            devanagariCount > 0 -> "hi"
            latinCount > 0 -> "en"
            else -> "unknown"
        }
    }
}
