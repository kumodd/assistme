package com.personalai.primary.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSpeechLanguageResolverTest {

    @Test
    fun `AUTO mode falls back seamlessly to installed en-US when en-IN is missing`() {
        val plan = OfflineSpeechLanguageResolver.resolve(
            mode = AsrLanguageMode.AUTO,
            installedLanguages = listOf("en-US"),
            pendingLanguages = emptyList(),
            supportedLanguages = listOf("en-IN", "en-US", "en-GB", "hi-IN"),
            deviceLocaleTag = "en-US"
        )

        assertEquals("en-US", plan.primaryCandidateTag)
        assertEquals(listOf("en-US"), plan.candidateTags)
        assertFalse(plan.canEnableMultiLanguageDetection)
        assertEquals(listOf("en-IN", "hi-IN"), plan.missingPreferredTags)
    }

    @Test
    fun `AUTO mode enables bilingual detection only when both English and Hindi packs are installed`() {
        val plan = OfflineSpeechLanguageResolver.resolve(
            mode = AsrLanguageMode.AUTO,
            installedLanguages = listOf("en-US", "hi-IN"),
            pendingLanguages = emptyList(),
            supportedLanguages = listOf("en-IN", "en-US", "hi-IN"),
            deviceLocaleTag = "en-US"
        )

        assertEquals("en-US", plan.primaryCandidateTag)
        assertTrue(plan.canEnableMultiLanguageDetection)
        assertEquals(listOf("en-US", "hi-IN"), plan.allowedDetectionTags)
        assertEquals(listOf("en-IN"), plan.missingPreferredTags)
    }

    @Test
    fun `AUTO mode prefers en-IN over en-US when both are installed and needs zero downloads if hi-IN also present`() {
        val plan = OfflineSpeechLanguageResolver.resolve(
            mode = AsrLanguageMode.AUTO,
            installedLanguages = listOf("en-US", "en-IN", "hi-IN"),
            pendingLanguages = emptyList(),
            supportedLanguages = listOf("en-IN", "en-US", "hi-IN"),
            deviceLocaleTag = "en-US"
        )

        assertEquals("en-IN", plan.primaryCandidateTag)
        assertTrue(plan.canEnableMultiLanguageDetection)
        assertEquals(listOf("en-IN", "hi-IN"), plan.allowedDetectionTags)
        assertTrue(plan.missingPreferredTags.isEmpty())
    }

    @Test
    fun `zero installed packs picks supported device locale as primary download`() {
        val plan = OfflineSpeechLanguageResolver.resolve(
            mode = AsrLanguageMode.AUTO,
            installedLanguages = emptyList(),
            pendingLanguages = emptyList(),
            supportedLanguages = listOf("en-IN", "en-US", "hi-IN"),
            deviceLocaleTag = "en_US"
        )

        assertTrue(plan.candidateTags.isEmpty())
        assertFalse(plan.canEnableMultiLanguageDetection)
        assertEquals("en-US", plan.primaryDownloadTag)
        assertEquals(listOf("en-IN", "hi-IN"), plan.missingPreferredTags)
    }

    @Test
    fun `case insensitive matching preserves canonical BCP-47 tags from RecognitionSupport`() {
        val plan = OfflineSpeechLanguageResolver.resolve(
            mode = AsrLanguageMode.ENGLISH,
            installedLanguages = listOf("en-us", "en-gb"),
            pendingLanguages = emptyList(),
            supportedLanguages = listOf("en-us", "en-gb"),
            deviceLocaleTag = "en_US"
        )

        assertEquals("en-us", plan.primaryCandidateTag)
        assertEquals(listOf("en-us", "en-gb"), plan.candidateTags)
        assertFalse(plan.canEnableMultiLanguageDetection)
    }

    @Test
    fun `HINDI mode prefers hi-IN and falls back to installed en-US while requesting hi-IN download`() {
        val plan = OfflineSpeechLanguageResolver.resolve(
            mode = AsrLanguageMode.HINDI,
            installedLanguages = listOf("en-US"),
            pendingLanguages = emptyList(),
            supportedLanguages = listOf("en-US", "hi-IN"),
            deviceLocaleTag = "en-US"
        )

        assertEquals("en-US", plan.primaryCandidateTag)
        assertEquals("hi-IN", plan.primaryDownloadTag)
        assertEquals(listOf("hi-IN"), plan.missingPreferredTags)
        assertFalse(plan.canEnableMultiLanguageDetection)
    }

    @Test
    fun `fallbackProbeTags puts device locale before hardcoded defaults without duplicates`() {
        val tags = OfflineSpeechLanguageResolver.fallbackProbeTags(AsrLanguageMode.AUTO, "en_US")
        assertEquals(listOf("en-US", "en-IN", "en-GB", "hi-IN"), tags)
    }
}
