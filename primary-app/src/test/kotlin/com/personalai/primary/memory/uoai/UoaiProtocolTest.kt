package com.personalai.primary.memory.uoai

import com.personalai.primary.asr.AsrLanguageMode
import com.personalai.primary.asr.AsrRequest
import com.personalai.primary.asr.LocalWhisperAsrProvider
import com.personalai.primary.asr.UoaiAsrProvider
import com.personalai.primary.asr.UoaiAsrScript
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `parses UOAI audio transcription verbose_json, providers list, and audio SSE stream`() {
        val transcriptionRaw = """
            {
              "id": "asr-9e7c3114-58d2-4d30-a1a7-01a2b3c4d5e6",
              "task": "transcribe",
              "model": "gemma-4-e2b",
              "language": "en,hi",
              "duration": 4.2,
              "text": "भाई कल meeting है, तुम presentation भेज देना।",
              "segments": [
                {"id": 0, "start": 0.0, "end": 4.2, "text": "भाई कल meeting है, तुम presentation भेज देना।"}
              ],
              "elapsed_ms": 1420,
              "provider": "gemma4",
              "roman_hinglish": "Bhai kal meeting hai, tum presentation bhej dena.",
              "devanagari_hinglish": "भाई कल meeting है, तुम presentation भेज देना।"
            }
        """.trimIndent()
        val parsed = json.decodeFromString<UoaiAudioTranscriptionResponse>(transcriptionRaw)
        assertEquals("gemma4", parsed.provider)
        assertEquals("gemma-4-e2b", parsed.model)
        assertEquals("en,hi", parsed.language)
        assertEquals(1420L, parsed.elapsedMs)
        assertEquals(
            "Bhai kal meeting hai, tum presentation bhej dena.",
            UoaiAsrProvider.extractTranscriptText(parsed, UoaiAsrScript.ROMAN)
        )
        assertEquals(
            "भाई कल meeting है, तुम presentation भेज देना।",
            UoaiAsrProvider.extractTranscriptText(parsed, UoaiAsrScript.CANONICAL)
        )
        assertEquals("UOAI Gemma 4 • gemma-4-e2b", UoaiAsrProvider.formatProviderLabel(parsed, "gemma4"))
        assertTrue(UoaiAsrProvider.computeConfidence(parsed, "gemma4") >= 0.90f)

        val providersRaw = """
            {
              "object": "list",
              "preferred_provider": "gemma4",
              "data": [
                {
                  "id": "gemma4",
                  "name": "Gemma 4 Direct Multimodal (On-Device)",
                  "kind": "local_multimodal_llm",
                  "builtin": true,
                  "enabled": true,
                  "ready": true,
                  "recommended": true,
                  "default_model": "gemma-4-e2b",
                  "models": ["gemma-4-e2b", "gemma-4"],
                  "supports_streaming": true
                },
                {
                  "id": "remote-gemma4",
                  "name": "Self-Hosted Gemma 4 Server",
                  "kind": "openai_compatible_chat_audio",
                  "builtin": false,
                  "enabled": true,
                  "ready": true,
                  "default_model": "gemma-4-e4b",
                  "has_api_key": true
                }
              ]
            }
        """.trimIndent()
        val providersResp = json.decodeFromString<UoaiAudioProvidersResponse>(providersRaw)
        assertEquals("gemma4", providersResp.preferredProvider)
        assertEquals(2, providersResp.data.size)
        assertTrue(providersResp.data[0].builtin && providersResp.data[0].ready)
        assertFalse(providersResp.data[1].builtin)
        assertTrue(providersResp.data[1].hasApiKey)

        // Audio SSE events
        val startEv = UoaiProtocolHelpers.parseAudioSseLine(
            """data: {"id":"asr-123","object":"audio.transcription.start","model":"gemma-4-e2b","language":"en,hi","provider":"gemma4"}"""
        ) as UoaiAudioStreamEvent.Started
        assertEquals("gemma-4-e2b", startEv.model)

        val segEv = UoaiProtocolHelpers.parseAudioSseLine(
            """data: {"id":"asr-123","object":"audio.transcription.segment","index":0,"start":0.0,"end":4.2,"text":"Bhai kal meeting hai.","language":"en,hi","is_final":true,"provider":"gemma4"}"""
        ) as UoaiAudioStreamEvent.Segment
        assertEquals("Bhai kal meeting hai.", segEv.text)
        assertTrue(segEv.isFinal)

        val doneEv = UoaiProtocolHelpers.parseAudioSseLine(
            """data: {"id":"asr-123","object":"audio.transcription.done","model":"gemma-4-e2b","language":"en,hi","duration":4.2,"text":"Bhai kal meeting hai.","finish_reason":"stop","provider":"gemma4"}"""
        ) as UoaiAudioStreamEvent.Completed
        assertEquals("stop", doneEv.finishReason)
        assertEquals(UoaiAudioStreamEvent.Done, UoaiProtocolHelpers.parseAudioSseLine("data: [DONE]"))
    }

    @Test
    fun `maps AsrRequest to UOAI Audio params, resolves default models, and formats actionable errors`() {
        val gemmaReq = AsrRequest(
            segmentId = "seg-1",
            wavBytes = ByteArray(16),
            languageMode = AsrLanguageMode.HINGLISH,
            uoaiProviderId = "gemma4",
            uoaiScript = UoaiAsrScript.ROMAN
        )
        val gemmaParams = UoaiAsrProvider.buildParams(gemmaReq)
        assertEquals("gemma4", gemmaParams.provider)
        assertEquals("gemma-4", gemmaParams.model)
        assertEquals("en,hi", gemmaParams.language)
        assertEquals("roman", gemmaParams.script)
        assertEquals("verbose_json", gemmaParams.responseFormat)

        val autoParams = UoaiAsrProvider.buildParams(
            gemmaReq.copy(languageMode = AsrLanguageMode.AUTO, uoaiProviderId = "auto")
        )
        assertEquals("auto", autoParams.provider)
        assertNull(autoParams.model)
        assertEquals("auto", autoParams.language)

        val externalParams = UoaiAsrProvider.buildParams(
            gemmaReq.copy(uoaiProviderId = "remote-gemma4", uoaiModel = "")
        )
        assertEquals("remote-gemma4", externalParams.provider)
        assertNull(externalParams.model)

        val permMsg = UoaiAsrProvider.formatActionableError(UoaiException.PermissionDenied("denied"))
        assertTrue(permMsg.contains("speech_to_text"))
        val encMsg = UoaiAsrProvider.formatActionableError(
            UoaiException.ApiError("no encoder", code = "gemma4_audio_unsupported", status = 400)
        )
        assertTrue(encMsg.contains("Gemma 4"))

        // Verify raw audio/wav endpoint URL query parameters (bypasses Ktor 64 KiB multipart formFieldLimit)
        val rawUrl = UoaiClient.buildAudioEndpointUrl("${UoaiConstants.BASE_URL}/audio/transcriptions", gemmaParams)
        assertEquals("127.0.0.1", rawUrl.host)
        assertEquals(8765, rawUrl.port)
        assertEquals("gemma4", rawUrl.queryParameter("provider"))
        assertEquals("gemma-4", rawUrl.queryParameter("model"))
        assertEquals("en,hi", rawUrl.queryParameter("language"))
        assertEquals("roman", rawUrl.queryParameter("script"))
        assertEquals("verbose_json", rawUrl.queryParameter("response_format"))
    }
}
