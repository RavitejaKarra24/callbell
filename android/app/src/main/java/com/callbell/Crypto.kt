package com.callbell

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Packet format (shared with the Mac app):
 *   3-byte magic | 12-byte nonce | AES-256-GCM ciphertext | 16-byte tag
 * The magic is also authenticated as associated data.
 * Key = SHA-256("callbell-v1:" + pairing code, upper-cased, letters/digits only).
 */
object Crypto {
    val MAGIC_EVENT = "CB1".toByteArray(Charsets.US_ASCII)
    val MAGIC_ACK = "CBA".toByteArray(Charsets.US_ASCII)
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun normalize(code: String): String =
        code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun keyFromCode(code: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("callbell-v1:${normalize(code)}".toByteArray(Charsets.UTF_8))

    fun seal(key: ByteArray, magic: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(magic)
        return magic + nonce + cipher.doFinal(plain)
    }

    fun open(key: ByteArray, magic: ByteArray, packet: ByteArray): ByteArray? {
        if (packet.size < magic.size + NONCE_LEN + TAG_BITS / 8) return null
        if (!packet.copyOfRange(0, magic.size).contentEquals(magic)) return null
        return try {
            val nonce = packet.copyOfRange(magic.size, magic.size + NONCE_LEN)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(magic)
            cipher.doFinal(packet, magic.size + NONCE_LEN, packet.size - magic.size - NONCE_LEN)
        } catch (e: Exception) {
            null
        }
    }
}
