package com.xcloak.storevia.data.backup

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphraseException : Exception("Wrong passphrase or damaged backup")

/**
 * Optional passphrase protection for backup files.
 * Format: "SVB1" | iterations(4) | salt(16) | iv(12) | AES-256-GCM ciphertext+tag.
 * The key comes from PBKDF2-HMAC-SHA256 (hand-rolled on javax.crypto.Mac so it works on every Android version),
 * and the header is authenticated as GCM associated data, so tampering is detected.
 */
object BackupCrypto {
    private val MAGIC = "SVB1".toByteArray()
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val HEADER_LEN = 4 + 4 + SALT_LEN + IV_LEN
    const val DEFAULT_ITERATIONS = 300_000

    fun isEncrypted(data: ByteArray): Boolean = data.size > HEADER_LEN && data.copyOfRange(0, 4).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, passphrase: String, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(passphrase.isNotEmpty())
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(rnd::nextBytes)
        val iv = ByteArray(IV_LEN).also(rnd::nextBytes)
        val header = ByteBuffer.allocate(HEADER_LEN).put(MAGIC).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pbkdf2Sha256(passphrase.toByteArray(Charsets.UTF_8), salt, iterations, 32), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray, passphrase: String): ByteArray {
        if (!isEncrypted(data) || passphrase.isEmpty()) throw WrongPassphraseException()
        val buf = ByteBuffer.wrap(data)
        buf.position(4)
        val iterations = buf.int
        if (iterations !in 1..5_000_000) throw WrongPassphraseException()
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val iv = ByteArray(IV_LEN).also { buf.get(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(pbkdf2Sha256(passphrase.toByteArray(Charsets.UTF_8), salt, iterations, 32), "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(data.copyOfRange(0, HEADER_LEN))
            cipher.doFinal(data, HEADER_LEN, data.size - HEADER_LEN)
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseException()
        }
    }

    /** PBKDF2 with HMAC-SHA256 (RFC 8018). Verified against the standard test vectors. */
    internal fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val blocks = (dkLen + 31) / 32
        val out = ByteArray(blocks * 32)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            t.copyInto(out, (i - 1) * 32)
        }
        return out.copyOf(dkLen)
    }
}
