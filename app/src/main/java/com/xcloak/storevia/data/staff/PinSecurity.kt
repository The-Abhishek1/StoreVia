package com.xcloak.storevia.data.staff

import com.xcloak.storevia.data.backup.BackupCrypto
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PINs are never stored: only a salted PBKDF2-HMAC-SHA256 hash. A 4-6 digit PIN has little entropy, so this slows
 * guessing but cannot stop someone who copies the database file. It separates who-did-what on a shared phone;
 * the app lock (the phone's own screen lock) and encrypted backups protect against theft of the device or file.
 */
object PinHasher {
    const val ITERATIONS = 120_000

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray = BackupCrypto.pbkdf2Sha256(pin.toByteArray(Charsets.UTF_8), salt, iterations, 32)
    fun verify(pin: String, salt: ByteArray, iterations: Int, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt, iterations), expected)   // constant-time compare

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun fromHex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

object PinPolicy {
    enum class Result { OK, BAD_LENGTH, NOT_DIGITS, TOO_SIMPLE }

    fun validate(pin: String): Result {
        if (pin.length !in 4..6) return Result.BAD_LENGTH
        if (!pin.all { it in '0'..'9' }) return Result.NOT_DIGITS
        val d = pin.map { it - '0' }
        val allSame = d.all { it == d[0] }
        val ascending = d.zipWithNext().all { (a, b) -> b == a + 1 }
        val descending = d.zipWithNext().all { (a, b) -> b == a - 1 }
        return if (allSame || ascending || descending) Result.TOO_SIMPLE else Result.OK
    }
}

object Lockout {
    const val FREE_ATTEMPTS = 5
    private const val MAX_MS = 15 * 60_000L

    /** After 5 wrong PINs in a row every further wrong try locks the account for longer: 30 s, 1 min, 2 min ... up to 15 min. */
    fun lockMillis(failedAttempts: Int): Long {
        if (failedAttempts < FREE_ATTEMPTS) return 0
        val step = (failedAttempts / FREE_ATTEMPTS - 1).coerceAtMost(10)
        return (30_000L shl step).coerceAtMost(MAX_MS)
    }
}
