package com.personalai.primary.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PrimaryKeyBundle(
    val clientDeviceId: String,
    val clientDeviceName: String,
    val pairedServerDeviceId: String? = null,
    val masterKeyBase64: String? = null,
    val uoaiToken: String? = null,
    val uoaiClientId: String? = null
)

@Singleton
class PrimaryCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "personal_ai_primary_kek_v1"
        private const val FILE_NAME = "primary_creds_v1.enc"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val credsFile: File get() = File(context.filesDir, FILE_NAME)
    private var cached: PrimaryKeyBundle? = null

    fun getOrCreateIdentity(): PrimaryKeyBundle = synchronized(lock) {
        val loaded = cached ?: loadFromDisk()
        if (loaded != null) {
            cached = loaded
            return loaded
        }
        val created = PrimaryKeyBundle(
            clientDeviceId = UUID.randomUUID().toString(),
            clientDeviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        saveToDisk(created)
        cached = created
        created
    }

    fun savePairedMasterKey(serverDeviceId: String, masterKey: ByteArray) = synchronized(lock) {
        val current = getOrCreateIdentity()
        val updated = current.copy(
            pairedServerDeviceId = serverDeviceId,
            masterKeyBase64 = Base64.encodeToString(masterKey, Base64.NO_WRAP)
        )
        saveToDisk(updated)
        cached = updated
    }

    fun getMasterKey(serverDeviceId: String? = null): ByteArray? = synchronized(lock) {
        val current = getOrCreateIdentity()
        if (serverDeviceId != null && current.pairedServerDeviceId != serverDeviceId) {
            return null
        }
        current.masterKeyBase64?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    fun clearPairing() = synchronized(lock) {
        val current = getOrCreateIdentity()
        val updated = current.copy(pairedServerDeviceId = null, masterKeyBase64 = null)
        saveToDisk(updated)
        cached = updated
    }

    fun saveUoaiCredentials(token: String, clientId: String?) = synchronized(lock) {
        val current = getOrCreateIdentity()
        val updated = current.copy(
            uoaiToken = token.trim(),
            uoaiClientId = clientId?.trim()?.takeIf { it.isNotEmpty() }
        )
        saveToDisk(updated)
        cached = updated
    }

    fun getUoaiToken(): String? = synchronized(lock) {
        getOrCreateIdentity().uoaiToken
    }

    fun getUoaiClientId(): String? = synchronized(lock) {
        getOrCreateIdentity().uoaiClientId
    }

    fun clearUoaiCredentials() = synchronized(lock) {
        val current = getOrCreateIdentity()
        val updated = current.copy(uoaiToken = null, uoaiClientId = null)
        saveToDisk(updated)
        cached = updated
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
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

    private fun saveToDisk(bundle: PrimaryKeyBundle) {
        val raw = json.encodeToString(bundle).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv
        val ct = cipher.doFinal(raw)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { fos ->
            val dos = DataOutputStream(fos)
            dos.writeInt(iv.size)
            dos.write(iv)
            dos.writeInt(ct.size)
            dos.write(ct)
            dos.flush()
            runCatching { fos.fd.sync() }
        }
        if (credsFile.exists()) credsFile.delete()
        tmp.renameTo(credsFile)
    }

    private fun loadFromDisk(): PrimaryKeyBundle? {
        if (!credsFile.exists()) return null
        return runCatching {
            DataInputStream(FileInputStream(credsFile)).use { dis ->
                val iv = ByteArray(dis.readInt())
                dis.readFully(iv)
                val ct = ByteArray(dis.readInt())
                dis.readFully(ct)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                }
                val plain = cipher.doFinal(ct).toString(Charsets.UTF_8)
                json.decodeFromString<PrimaryKeyBundle>(plain)
            }
        }.getOrNull()
    }
}
