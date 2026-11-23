package com.personalai.recorder.ble

import android.content.Context
import android.util.Base64
import com.personalai.protocol.PairingExchangeRequestPayload
import com.personalai.protocol.PairingExchangeResponsePayload
import com.personalai.protocol.ProtocolCrypto
import com.personalai.recorder.security.MasterKeyProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPair
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PersistedPairingRecord(
    val pairedClientDeviceId: String,
    val pairedClientName: String,
    val pairedBondAddress: String?,
    val masterKeyBase64: String,
    val pairedAtMs: Long
)

data class PendingPairingChallenge(
    val clientDeviceId: String,
    val clientDeviceName: String,
    val sasCode: String,
    val derivedMasterKey: ByteArray
)

data class PairingUiSnapshot(
    val isPaired: Boolean = false,
    val pairedClientDeviceId: String? = null,
    val pairedClientName: String? = null,
    val pairedBondAddress: String? = null,
    val pairingWindowOpen: Boolean = false,
    val pendingClientId: String? = null,
    val pendingClientName: String? = null,
    val pendingSasCode: String? = null,
    val pairingQrJson: String? = null
)

@Singleton
class PairingCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val masterKeyProvider: MasterKeyProvider
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val credsFile: File
        get() = File(context.filesDir, "ble_pairing_creds_v1.enc")

    private val lock = Any()
    private var activeEphemeralKeyPair: KeyPair? = null
    private var pendingChallenge: PendingPairingChallenge? = null
    private var cachedRecord: PersistedPairingRecord? = loadFromDisk()

    private val _uiState = MutableStateFlow(buildSnapshot(windowOpen = false))
    val uiState: StateFlow<PairingUiSnapshot> = _uiState.asStateFlow()

    fun getMasterKeyBytes(): ByteArray? = synchronized(lock) {
        cachedRecord?.masterKeyBase64?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    fun getPairedClientId(): String? = synchronized(lock) {
        cachedRecord?.pairedClientDeviceId
    }

    fun openPairingWindow(serverDeviceId: String, serverDeviceName: String): String = synchronized(lock) {
        val kp = ProtocolCrypto.generateEcKeyPair()
        activeEphemeralKeyPair = kp
        pendingChallenge = null
        val pubB64 = ProtocolCrypto.encodePublicKeyBase64(kp.public)
        val qrJson = json.encodeToString(
            com.personalai.protocol.PairingQrPayload(
                deviceId = serverDeviceId,
                deviceName = serverDeviceName,
                publicKeyBase64 = pubB64
            )
        )
        _uiState.value = buildSnapshot(windowOpen = true, qrJson = qrJson)
        qrJson
    }

    fun closePairingWindow() = synchronized(lock) {
        activeEphemeralKeyPair = null
        pendingChallenge = null
        _uiState.value = buildSnapshot(windowOpen = false)
    }

    fun processIncomingPairExchange(
        serverDeviceId: String,
        serverDeviceName: String,
        req: PairingExchangeRequestPayload
    ): PairingExchangeResponsePayload = synchronized(lock) {
        val kp = activeEphemeralKeyPair ?: ProtocolCrypto.generateEcKeyPair().also {
            activeEphemeralKeyPair = it
        }
        val clientPub = ProtocolCrypto.decodePublicKeyBase64(req.clientPublicKeyBase64)
        val sharedSecret = ProtocolCrypto.computeEcdhSharedSecret(kp.private, clientPub)
        val derivedMasterKey = ProtocolCrypto.deriveMasterKey(
            sharedSecret = sharedSecret,
            clientDeviceId = req.clientDeviceId,
            serverDeviceId = serverDeviceId
        )
        val sasCode = ProtocolCrypto.computeSixDigitSas(
            sharedSecret = sharedSecret,
            clientPubEncoded = clientPub.encoded,
            serverPubEncoded = kp.public.encoded,
            serverDeviceId = serverDeviceId
        )

        pendingChallenge = PendingPairingChallenge(
            clientDeviceId = req.clientDeviceId,
            clientDeviceName = req.clientDeviceName,
            sasCode = sasCode,
            derivedMasterKey = derivedMasterKey
        )
        _uiState.update {
            it.copy(
                pairingWindowOpen = true,
                pendingClientId = req.clientDeviceId,
                pendingClientName = req.clientDeviceName,
                pendingSasCode = sasCode
            )
        }

        PairingExchangeResponsePayload(
            serverDeviceId = serverDeviceId,
            serverDeviceName = serverDeviceName,
            serverPublicKeyBase64 = ProtocolCrypto.encodePublicKeyBase64(kp.public),
            sasCode = sasCode,
            accepted = true
        )
    }

    fun approvePendingPairing(bondAddress: String?): PersistedPairingRecord? = synchronized(lock) {
        val pending = pendingChallenge ?: return null
        val record = PersistedPairingRecord(
            pairedClientDeviceId = pending.clientDeviceId,
            pairedClientName = pending.clientDeviceName,
            pairedBondAddress = bondAddress,
            masterKeyBase64 = Base64.encodeToString(pending.derivedMasterKey, Base64.NO_WRAP),
            pairedAtMs = System.currentTimeMillis()
        )
        saveToDisk(record)
        cachedRecord = record
        pendingChallenge = null
        activeEphemeralKeyPair = null
        _uiState.value = buildSnapshot(windowOpen = false)
        record
    }

    fun rejectPendingPairing() = synchronized(lock) {
        pendingChallenge = null
        _uiState.update {
            it.copy(pendingClientId = null, pendingClientName = null, pendingSasCode = null)
        }
    }

    fun unpair() = synchronized(lock) {
        cachedRecord = null
        pendingChallenge = null
        activeEphemeralKeyPair = null
        credsFile.delete()
        _uiState.value = buildSnapshot(windowOpen = false)
    }

    private fun buildSnapshot(windowOpen: Boolean, qrJson: String? = null): PairingUiSnapshot {
        val rec = cachedRecord
        return PairingUiSnapshot(
            isPaired = rec != null,
            pairedClientDeviceId = rec?.pairedClientDeviceId,
            pairedClientName = rec?.pairedClientName,
            pairedBondAddress = rec?.pairedBondAddress,
            pairingWindowOpen = windowOpen,
            pendingClientId = pendingChallenge?.clientDeviceId,
            pendingClientName = pendingChallenge?.clientDeviceName,
            pendingSasCode = pendingChallenge?.sasCode,
            pairingQrJson = qrJson
        )
    }

    private fun saveToDisk(record: PersistedPairingRecord) {
        val raw = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        val kek = masterKeyProvider.getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, kek)
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(raw)
        val tmp = File(context.filesDir, "ble_pairing_creds_v1.enc.tmp")
        FileOutputStream(tmp).use { fos ->
            val dos = DataOutputStream(fos)
            dos.writeInt(iv.size)
            dos.write(iv)
            dos.writeInt(ciphertext.size)
            dos.write(ciphertext)
            dos.flush()
            runCatching { fos.fd.sync() }
        }
        if (credsFile.exists()) credsFile.delete()
        tmp.renameTo(credsFile)
    }

    private fun loadFromDisk(): PersistedPairingRecord? {
        if (!credsFile.exists()) return null
        return runCatching {
            DataInputStream(FileInputStream(credsFile)).use { dis ->
                val ivLen = dis.readInt()
                val iv = ByteArray(ivLen)
                dis.readFully(iv)
                val ctLen = dis.readInt()
                val ct = ByteArray(ctLen)
                dis.readFully(ct)
                val kek = masterKeyProvider.getOrCreateSecretKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, iv))
                }
                val plain = cipher.doFinal(ct).toString(Charsets.UTF_8)
                json.decodeFromString<PersistedPairingRecord>(plain)
            }
        }.getOrNull()
    }
}
