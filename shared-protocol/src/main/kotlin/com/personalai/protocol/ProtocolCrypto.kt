package com.personalai.protocol

import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SessionKeys(
    val hmacKey: ByteArray,
    val encryptionKey: ByteArray
)

object ProtocolCrypto {
    private const val EC_CURVE = "secp256r1"
    private const val HMAC_ALG = "HmacSHA256"

    fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        return kpg.generateKeyPair()
    }

    fun encodePublicKeyBase64(publicKey: PublicKey): String {
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    fun decodePublicKeyBase64(encoded: String): PublicKey {
        val bytes = Base64.getDecoder().decode(encoded)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun computeEcdhSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    fun deriveMasterKey(
        sharedSecret: ByteArray,
        clientDeviceId: String,
        serverDeviceId: String
    ): ByteArray {
        val salt = "$clientDeviceId|$serverDeviceId".toByteArray(Charsets.UTF_8)
        val info = "PersonalAiMemory-MasterPairingKey-v1".toByteArray(Charsets.UTF_8)
        return hkdfSha256(ikm = sharedSecret, salt = salt, info = info, length = 32)
    }

    fun computeSixDigitSas(
        sharedSecret: ByteArray,
        clientPubEncoded: ByteArray,
        serverPubEncoded: ByteArray,
        serverDeviceId: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(sharedSecret)
            update(clientPubEncoded)
            update(serverPubEncoded)
            update(serverDeviceId.toByteArray(Charsets.UTF_8))
        }.digest()
        val value = ByteBuffer.wrap(digest, 0, 4).int and 0x7FFFFFFF
        val code = value % 1_000_000
        return code.toString().padStart(6, '0')
    }

    fun deriveSessionKeys(
        masterKey: ByteArray,
        serverNonce: ByteArray,
        clientNonce: ByteArray
    ): SessionKeys {
        val salt = ByteArray(serverNonce.size + clientNonce.size)
        System.arraycopy(serverNonce, 0, salt, 0, serverNonce.size)
        System.arraycopy(clientNonce, 0, salt, serverNonce.size, clientNonce.size)
        val okm = hkdfSha256(
            ikm = masterKey,
            salt = salt,
            info = "PersonalAiMemory-SessionKeys-v1".toByteArray(Charsets.UTF_8),
            length = 64
        )
        return SessionKeys(
            hmacKey = okm.copyOfRange(0, 32),
            encryptionKey = okm.copyOfRange(32, 64)
        )
    }

    fun computeChallengeResponse(
        masterKey: ByteArray,
        serverNonce: ByteArray,
        clientNonce: ByteArray,
        clientDeviceId: String
    ): ByteArray {
        val payload = ByteBuffer.allocate(serverNonce.size + clientNonce.size + clientDeviceId.length)
            .put(serverNonce)
            .put(clientNonce)
            .put(clientDeviceId.toByteArray(Charsets.UTF_8))
            .array()
        return hmacSha256(masterKey, payload)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(key, HMAC_ALG))
        return mac.doFinal(data)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

    fun deviceTag8Bytes(deviceId: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 8)
    }

    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(effectiveSalt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance(HMAC_ALG)
            mac.init(SecretKeySpec(prk, HMAC_ALG))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, copyLen)
            offset += copyLen
            counter++
        }
        return out
    }
}
