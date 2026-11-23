package com.personalai.recorder.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedStorageInstrumentationTest {

    @Test
    fun androidKeystoreEncryptionRoundTripWorks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyProvider = AndroidKeystoreMasterKeyProvider()
        val crypto = SegmentCrypto(keyProvider)
        val storage = EncryptedSegmentStorage(context, crypto)

        val pcm = ShortArray(16_000) { idx -> ((idx * 37) % 20_000).toShort() }
        val stored = storage.writeEncryptedWav(
            segmentId = "inst-seg-1",
            sessionId = "inst-sess-1",
            sequenceNumber = 999L,
            pcmSamples = pcm,
            sampleRateHz = 16_000,
            channels = 1
        )
        val expectedWav = WavHeaderWriter.pcm16ShortsToWavBytes(pcm, 16_000, 1)
        assertEquals(WavHeaderWriter.sha256Hex(expectedWav), stored.sha256Checksum)

        val segment = com.personalai.recorder.data.db.AudioSegmentEntity(
            segmentId = "inst-seg-1",
            sessionId = "inst-sess-1",
            sequenceNumber = 999L,
            startTimestamp = 1000L,
            endTimestamp = 2000L,
            duration = 1000L,
            filePath = stored.relativePath,
            fileSize = stored.plaintextSize,
            encryptedSize = stored.encryptedSize,
            checksum = stored.sha256Checksum,
            uploadStatus = com.personalai.recorder.data.db.UploadStatus.QUEUED,
            createdAt = 2000L
        )
        val decrypted = storage.decryptAndVerify(segment)
        assertArrayEquals(expectedWav, decrypted)
        storage.deleteSegmentFile(stored.relativePath)
    }
}
