package com.personalai.primary.memory.uoai

import com.personalai.primary.asr.LocalWhisperAsrProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UoaiProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `parses SSE tokens, DONE, cancelled, and mid-stream error events`() {
        assertEquals(UoaiSseEvent.Ignore, UoaiProtocolHelpers.parseSseLine(""))
        assertEquals(UoaiSseEvent.Ignore, UoaiProtocolHelpers.parseSseLine(": keep-alive"))

        val roleLine = """data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gemma-4-e2b","choices":[{"index":0,"delta":{"role":"assistant"}}]}"""
        assertEquals(UoaiSseEvent.Ignore, UoaiProtocolHelpers.parseSseLine(roleLine))

        val tokenLine = """data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gemma-4-e2b","choices":[{"index":0,"delta":{"content":"Hello"}}]}"""
        assertEquals(UoaiSseEvent.Token("Hello"), UoaiProtocolHelpers.parseSseLine(tokenLine))

        val cancelLine = """data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gemma-4-e2b","choices":[{"index":0,"delta":{},"finish_reason":"cancelled"}]}"""
        assertEquals(UoaiSseEvent.Cancelled, UoaiProtocolHelpers.parseSseLine(cancelLine))

        val errLine = """data: {"error":{"message":"Timed out","type":"timeout","code":"request_timeout"}}"""
        val parsedErr = UoaiProtocolHelpers.parseSseLine(errLine) as UoaiSseEvent.StreamError
        assertEquals("request_timeout", parsedErr.code)
        assertEquals("Timed out", parsedErr.message)

        assertEquals(UoaiSseEvent.Done, UoaiProtocolHelpers.parseSseLine("data: [DONE]"))
    }

    @Test
    fun `budgets max_tokens dynamically against 32K capabilities context window`() {
        val caps32k = UoaiCapabilities(
            features = listOf("chat", "streaming", "cancellation", "tool-calling"),
            maxTokensLimit = 32_768,
            contextWindowTokens = 32_768,
            defaultMaxTokens = 2_048
        )
        assertEquals(
            1_536,
            UoaiProtocolHelpers.computeEffectiveMaxTokens(1_536, caps32k, estimatedPromptTokens = 500)
        )
        // Tight context window clamps output safely without overflowing
        val capsTight = UoaiCapabilities(maxTokensLimit = 4_096, contextWindowTokens = 2_048)
        assertEquals(
            320,
            UoaiProtocolHelpers.computeEffectiveMaxTokens(1_536, capsTight, estimatedPromptTokens = 1_600)
        )
        assertTrue(caps32k.supportsStreaming && caps32k.supportsCancellation && caps32k.supportsToolCalling)
    }

    @Test
    fun `extracts strict JSON object from fenced Gemma outputs and serializes auto model request`() {
        val fenced = """
            Sure, here is the JSON:
            ```json
            {"memories":[{"category":"TASK","title":"Send deck","content":"Send deck tomorrow"}]}
            ```
        """.trimIndent()
        val extracted = UoaiProtocolHelpers.extractJsonObject(fenced)
        assertTrue(extracted.startsWith("{") && extracted.endsWith("}"))
        assertTrue(extracted.contains("\"Send deck\""))

        val req = UoaiChatCompletionRequest(
            messages = listOf(UoaiChatMessage("user", "Hello")),
            stream = true
        )
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("\"model\":\"auto\""))
        assertTrue(encoded.contains("\"stream\":true"))
    }

    @Test
    fun `privacy guard allows only loopback whisper hosts and blocks cloud URLs`() {
        assertEquals(
            "http://127.0.0.1:8080/v1/audio/transcriptions",
            LocalWhisperAsrProvider.validateLoopbackEndpoint("http://127.0.0.1:8080")
        )
        assertEquals(
            "http://localhost:9000/v1/audio/transcriptions",
            LocalWhisperAsrProvider.validateLoopbackEndpoint("http://localhost:9000/")
        )

        val blocked = runCatching {
            LocalWhisperAsrProvider.validateLoopbackEndpoint("https://api.openai.com")
        }
        assertTrue("Cloud host must be blocked by privacy guard", blocked.exceptionOrNull() is SecurityException)

        val blockedLan = runCatching {
            LocalWhisperAsrProvider.validateLoopbackEndpoint("http://192.168.1.50:8080")
        }
        assertTrue("Non-loopback host must be blocked by privacy guard", blockedLan.exceptionOrNull() is SecurityException)
    }
}
