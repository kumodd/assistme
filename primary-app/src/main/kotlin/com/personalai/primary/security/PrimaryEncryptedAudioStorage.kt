package com.personalai.primary.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrimaryEncryptedAudioStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AUDIO_KEY_ALIAS = "personal_ai_primary_audio_kek_v1"
        private const val ALGORITHM = "AES/GCM/NoPadding"
    }

    private val secureRandom = SecureRandom()
    private val audioBaseDir: File get() = File(context.filesDir, "synced_audio").apply { mkdirs() }
    private val stagingDir: File get() = File(context.filesDir, "sync_staging").apply { mkdirs() }

    fun getPartFile(segmentId: String): File = File(stagingDir, "$segmentId.wav.part")

    fun deletePartFile(segmentId: String) {
        getPartFile(segmentId).delete()
    }

    fun sha256FileHex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16_384)
        FileInputStream(file).use { fis ->
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun encryptFromWavFile(sessionId: String, segmentId: String, wavFile: File): String {
        val wavBytes = wavFile.readBytes()
        val sessionDir = File(audioBaseDir, sessionId).apply { mkdirs() }
        val finalFile = File(sessionDir, "$segmentId.enc")
        val tempFile = File(sessionDir, "$segmentId.enc.tmp")

        val rawDek = ByteArray(32).also(secureRandom::nextBytes)
        val dekSpec = SecretKeySpec(rawDek, "AES")
        val wrapCipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        }
        val wrapIv = wrapCipher.iv
        val wrappedDek = wrapCipher.doFinal(rawDek)
        rawDek.fill(0)

        val payloadIv = ByteArray(12).also(secureRandom::nextBytes)
        val dataCipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, dekSpec, GCMParameterSpec(128, payloadIv))
            updateAAD("$sessionId|$segmentId".toByteArray(Charsets.UTF_8))
        }
        val ciphertext = dataCipher.doFinal(wavBytes)

        try {
            FileOutputStream(tempFile).use { fos ->
                val dos = DataOutputStream(fos)
                dos.writeInt(wrapIv.size)
                dos.write(wrapIv)
                dos.writeInt(wrappedDek.size)
                dos.write(wrappedDek)
                dos.writeInt(payloadIv.size)
                dos.write(payloadIv)
                dos.writeInt(ciphertext.size)
                dos.write(ciphertext)
                dos.flush()
                runCatching { fos.fd.sync() }
            }
            if (finalFile.exists()) finalFile.delete()
            check(tempFile.renameTo(finalFile)) { "Failed renaming encrypted synced audio" }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
        return "$sessionId/${finalFile.name}"
    }

    fun decryptWavBytes(relativePath: String, sessionId: String, segmentId: String): ByteArray {
        val target = File(audioBaseDir, relativePath)
        DataInputStream(FileInputStream(target)).use { dis ->
            val wrapIv = ByteArray(dis.readInt()).also(dis::readFully)
            val wrappedDek = ByteArray(dis.readInt()).also(dis::readFully)
            val payloadIv = ByteArray(dis.readInt()).also(dis::readFully)
            val ciphertext = ByteArray(dis.readInt()).also(dis::readFully)

            val unwrapCipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, wrapIv))
            }
            val rawDek = unwrapCipher.doFinal(wrappedDek)
            val dekSpec = SecretKeySpec(rawDek, "AES")
            rawDek.fill(0)

            val dataCipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.DECRYPT_MODE, dekSpec, GCMParameterSpec(128, payloadIv))
                updateAAD("$sessionId|$segmentId".toByteArray(Charsets.UTF_8))
            }
            return dataCipher.doFinal(ciphertext)
        }
    }

    fun deleteEncryptedAudio(relativePath: String): Long {
        if (relativePath.isBlank()) return 0L
        val target = File(audioBaseDir, relativePath)
        val freed = if (target.exists()) target.length() else 0L
        if (target.exists()) {
            target.delete()
        }
        val parent = target.parentFile
        if (parent != null && parent != audioBaseDir && parent.isDirectory && parent.listFiles().isNullOrEmpty()) {
            parent.delete()
        }
        return freed
    }

    fun hasEncryptedAudio(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        return File(audioBaseDir, relativePath).exists()
    }

    fun computeStoredAudioBytes(): Long {
        return audioBaseDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".enc") }
            .sumOf { it.length() }
    }

    fun wipeAllSyncedAudio() {
        audioBaseDir.deleteRecursively()
        stagingDir.deleteRecursively()
        audioBaseDir.mkdirs()
        stagingDir.mkdirs()
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(AUDIO_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            AUDIO_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }
}
