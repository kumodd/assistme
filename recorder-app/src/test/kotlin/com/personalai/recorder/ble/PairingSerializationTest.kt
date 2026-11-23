package com.personalai.recorder.ble

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingSerializationTest {

    @Test
    fun `persisted pairing record serializes and deserializes cleanly`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = PersistedPairingRecord(
            pairedClientDeviceId = "client-123",
            pairedClientName = "Motorola Edge 70 Fusion",
            pairedBondAddress = "4B:42:44:1C:9E:93",
            masterKeyBase64 = "dGVzdC1tYXN0ZXIta2V5",
            pairedAtMs = 1_760_000_000_000L
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PersistedPairingRecord>(encoded)
        assertEquals(original, decoded)
    }
}
