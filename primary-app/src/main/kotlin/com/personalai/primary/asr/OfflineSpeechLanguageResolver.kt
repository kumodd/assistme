package com.personalai.primary.asr

import java.util.Locale

data class OfflineSpeechPackStatus(
    val isSupportedByDevice: Boolean = true,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int? = null,
    val activeDownloadLanguage: String? = null,
    val installedLanguages: List<String> = emptyList(),
    val pendingLanguages: List<String> = emptyList(),
    val supportedLanguages: List<String> = emptyList(),
    val lastMessage: String? = null,
    val lastCheckedAtMs: Long = 0L
) {
    val hasAnyInstalledPack: Boolean
        get() = installedLanguages.isNotEmpty()
}

data class ResolvedSpeechLanguagePlan(
    val candidateTags: List<String>,
    val allowedDetectionTags: List<String>,
    val missingPreferredTags: List<String>,
    val primaryDownloadTag: String?
) {
    val primaryCandidateTag: String?
        get() = candidateTags.firstOrNull()

    val canEnableMultiLanguageDetection: Boolean
        get() = allowedDetectionTags.size >= 2
}

/**
 * Pure-Kotlin resolver that maps user ASR language mode + installed/supported Android on-device
 * speech packs to a safe, error-free execution plan without hardcoding a single missing locale
 * like "en-IN".
 */
object OfflineSpeechLanguageResolver {

    fun normalizeTag(tag: String): String {
        val cleaned = tag.trim().replace('_', '-')
        if (cleaned.isEmpty()) return ""
        val parts = cleaned.split('-').filter { it.isNotBlank() }
        return when (parts.size) {
            0 -> ""
            1 -> parts[0].lowercase(Locale.ROOT)
            else -> buildString {
                append(parts[0].lowercase(Locale.ROOT))
                for (i in 1 until parts.size) {
                    append('-')
                    val p = parts[i]
                    append(if (p.length == 2) p.uppercase(Locale.ROOT) else p)
                }
            }
        }
    }

    fun resolve(
        mode: AsrLanguageMode,
        installedLanguages: List<String>,
        pendingLanguages: List<String>,
        supportedLanguages: List<String>,
        deviceLocaleTag: String
    ): ResolvedSpeechLanguagePlan {
        val installedByKey = linkedMapOf<String, String>()
        installedLanguages.forEach { raw ->
            val key = normalizeTag(raw).lowercase(Locale.ROOT)
            if (key.isNotEmpty() && !installedByKey.containsKey(key)) {
                installedByKey[key] = raw.trim()
            }
        }

        val supportedByKey = linkedMapOf<String, String>()
        supportedLanguages.forEach { raw ->
            val key = normalizeTag(raw).lowercase(Locale.ROOT)
            if (key.isNotEmpty() && !supportedByKey.containsKey(key)) {
                supportedByKey[key] = raw.trim()
            }
        }

        val pendingKeys = pendingLanguages
            .map { normalizeTag(it).lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()

        val deviceNorm = normalizeTag(deviceLocaleTag)
        val deviceKey = deviceNorm.lowercase(Locale.ROOT)
        val deviceIsEn = deviceKey == "en" || deviceKey.startsWith("en-")
        val deviceIsHi = deviceKey == "hi" || deviceKey.startsWith("hi-")

        val candidates = mutableListOf<String>()
        fun addInstalledIfPresent(tag: String) {
            val key = normalizeTag(tag).lowercase(Locale.ROOT)
            val canonical = installedByKey[key] ?: return
            if (canonical !in candidates) candidates.add(canonical)
        }

        fun addInstalledByPrefix(prefix: String) {
            installedByKey.forEach { (key, canonical) ->
                if ((key == prefix || key.startsWith("$prefix-")) && canonical !in candidates) {
                    candidates.add(canonical)
                }
            }
        }

        when (mode) {
            AsrLanguageMode.AUTO, AsrLanguageMode.HINGLISH -> {
                addInstalledIfPresent("en-IN")
                if (deviceIsEn) addInstalledIfPresent(deviceNorm)
                addInstalledIfPresent("en-US")
                addInstalledIfPresent("en-GB")
                addInstalledByPrefix("en")
                addInstalledIfPresent("hi-IN")
                if (deviceIsHi) addInstalledIfPresent(deviceNorm)
                addInstalledByPrefix("hi")
                if (deviceNorm.isNotEmpty()) addInstalledIfPresent(deviceNorm)
                installedByKey.values.forEach { if (it !in candidates) candidates.add(it) }
            }

            AsrLanguageMode.ENGLISH -> {
                addInstalledIfPresent("en-IN")
                if (deviceIsEn) addInstalledIfPresent(deviceNorm)
                addInstalledIfPresent("en-US")
                addInstalledIfPresent("en-GB")
                addInstalledByPrefix("en")
                installedByKey.values.forEach { if (it !in candidates) candidates.add(it) }
            }

            AsrLanguageMode.HINDI -> {
                addInstalledIfPresent("hi-IN")
                if (deviceIsHi) addInstalledIfPresent(deviceNorm)
                addInstalledByPrefix("hi")
                // Fallback to installed English pack so Hinglish/English still transcribes while hi-IN downloads
                addInstalledIfPresent("en-IN")
                if (deviceIsEn) addInstalledIfPresent(deviceNorm)
                addInstalledIfPresent("en-US")
                addInstalledIfPresent("en-GB")
                addInstalledByPrefix("en")
                installedByKey.values.forEach { if (it !in candidates) candidates.add(it) }
            }
        }

        // Only enable API 34 multi-language detection when BOTH an installed English AND an installed Hindi pack exist.
        val allowedDetection = if (mode == AsrLanguageMode.AUTO || mode == AsrLanguageMode.HINGLISH) {
            val primaryEn = listOfNotNull(
                installedByKey["en-in"],
                if (deviceIsEn) installedByKey[deviceKey] else null,
                installedByKey["en-us"],
                installedByKey["en-gb"]
            ).firstOrNull() ?: installedByKey.entries.firstOrNull { it.key == "en" || it.key.startsWith("en-") }?.value

            val primaryHi = listOfNotNull(
                installedByKey["hi-in"],
                if (deviceIsHi) installedByKey[deviceKey] else null
            ).firstOrNull() ?: installedByKey.entries.firstOrNull { it.key == "hi" || it.key.startsWith("hi-") }?.value

            listOfNotNull(primaryEn, primaryHi).distinct()
        } else {
            emptyList()
        }

        fun pickSupported(vararg preferred: String): String? {
            for (tag in preferred) {
                val key = normalizeTag(tag).lowercase(Locale.ROOT)
                if (key.isEmpty()) continue
                supportedByKey[key]?.let { return it }
            }
            return null
        }

        val missingPreferred = mutableListOf<String>()
        fun maybeAddMissing(vararg preferred: String) {
            // If any of the preferred tags in this group is already installed, skip unless the very first is requested
            val target = if (supportedByKey.isNotEmpty()) {
                pickSupported(*preferred)
            } else {
                preferred.firstOrNull { it.isNotBlank() }?.let(::normalizeTag)
            } ?: return
            val targetKey = normalizeTag(target).lowercase(Locale.ROOT)
            if (!installedByKey.containsKey(targetKey) && !pendingKeys.contains(targetKey) && target !in missingPreferred) {
                missingPreferred.add(target)
            }
        }

        when (mode) {
            AsrLanguageMode.AUTO, AsrLanguageMode.HINGLISH -> {
                if (deviceIsEn) {
                    maybeAddMissing("en-IN", deviceNorm, "en-US", "en-GB")
                } else {
                    maybeAddMissing("en-IN", "en-US", "en-GB")
                }
                maybeAddMissing("hi-IN")
            }

            AsrLanguageMode.ENGLISH -> {
                if (deviceIsEn) {
                    maybeAddMissing("en-IN", deviceNorm, "en-US", "en-GB")
                } else {
                    maybeAddMissing("en-IN", "en-US", "en-GB")
                }
            }

            AsrLanguageMode.HINDI -> {
                maybeAddMissing("hi-IN")
            }
        }

        val primaryDownloadTag: String? = when (mode) {
            AsrLanguageMode.HINDI -> {
                if (installedByKey.keys.any { it == "hi" || it.startsWith("hi-") }) {
                    null
                } else {
                    if (supportedByKey.isNotEmpty()) {
                        pickSupported("hi-IN", deviceNorm, "en-US", "en-IN")
                            ?: supportedByKey.values.firstOrNull()
                    } else {
                        "hi-IN"
                    }
                }
            }

            AsrLanguageMode.ENGLISH -> {
                if (candidates.isNotEmpty()) {
                    missingPreferred.firstOrNull()
                } else if (supportedByKey.isNotEmpty()) {
                    pickSupported(if (deviceIsEn) deviceNorm else "", "en-US", "en-IN", "en-GB")
                        ?: supportedByKey.entries.firstOrNull { it.key.startsWith("en") }?.value
                        ?: supportedByKey.values.firstOrNull()
                } else {
                    if (deviceIsEn) deviceNorm else "en-US"
                }
            }

            AsrLanguageMode.AUTO, AsrLanguageMode.HINGLISH -> {
                if (candidates.isNotEmpty()) {
                    missingPreferred.firstOrNull()
                } else if (supportedByKey.isNotEmpty()) {
                    pickSupported(if (deviceIsEn || deviceIsHi) deviceNorm else "", "en-US", "en-IN", "en-GB", "hi-IN")
                        ?: supportedByKey.entries.firstOrNull { it.key.startsWith("en") }?.value
                        ?: supportedByKey.values.firstOrNull()
                } else {
                    if (deviceIsEn || deviceIsHi) deviceNorm else "en-US"
                }
            }
        }

        return ResolvedSpeechLanguagePlan(
            candidateTags = candidates,
            allowedDetectionTags = allowedDetection,
            missingPreferredTags = missingPreferred,
            primaryDownloadTag = primaryDownloadTag
        )
    }

    /**
     * Ordered fallback list used when checkRecognitionSupport fails or returns empty on an OEM ROM.
     */
    fun fallbackProbeTags(mode: AsrLanguageMode, deviceLocaleTag: String): List<String> {
        val deviceNorm = normalizeTag(deviceLocaleTag)
        val raw = when (mode) {
            AsrLanguageMode.HINDI -> listOf("hi-IN", deviceNorm, "en-US", "en-IN")
            AsrLanguageMode.ENGLISH -> listOf(deviceNorm, "en-US", "en-IN", "en-GB")
            AsrLanguageMode.AUTO, AsrLanguageMode.HINGLISH -> listOf(deviceNorm, "en-US", "en-IN", "en-GB", "hi-IN")
        }
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<String>()
        raw.forEach { tag ->
            if (tag.isNotBlank()) {
                val key = tag.lowercase(Locale.ROOT)
                if (seen.add(key)) result.add(tag)
            }
        }
        return result
    }

    fun defaultDownloadTag(mode: AsrLanguageMode, deviceLocaleTag: String): String {
        val deviceNorm = normalizeTag(deviceLocaleTag)
        val deviceIsEn = deviceNorm.lowercase(Locale.ROOT).let { it == "en" || it.startsWith("en-") }
        return when (mode) {
            AsrLanguageMode.HINDI -> "hi-IN"
            AsrLanguageMode.ENGLISH, AsrLanguageMode.AUTO, AsrLanguageMode.HINGLISH -> if (deviceIsEn) deviceNorm else "en-US"
        }
    }
}
