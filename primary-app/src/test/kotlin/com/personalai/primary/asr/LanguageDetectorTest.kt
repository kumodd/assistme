package com.personalai.primary.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun `detects English, Hindi Devanagari, and Hinglish accurately`() {
        assertEquals("en", LanguageDetector.normalizeAndRefine("english", "Let's finalize the project deadline tomorrow."))
        assertEquals("hi", LanguageDetector.normalizeAndRefine("hindi", "कल सुबह दस बजे मीटिंग है।"))
        assertEquals("hi-en", LanguageDetector.normalizeAndRefine("hi", "कल morning में project demo करना है"))
        assertEquals("hi-en", LanguageDetector.normalizeAndRefine("en,hi", "Bhai kal meeting hai, tum presentation bhej dena."))
        assertEquals("en", LanguageDetector.normalizeAndRefine("auto", "Schedule a call with Ravi"))
        assertEquals("en", LanguageDetector.normalizeAndRefine(null, "Schedule a call with Ravi"))
        assertEquals("hi", LanguageDetector.normalizeAndRefine(null, "आज शाम को कॉल करना"))
    }
}
