package com.xcloak.storevia.data

import com.xcloak.storevia.data.backup.BackupCrypto
import com.xcloak.storevia.data.backup.WrongPassphraseException
import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun pbkdf2MatchesRfcVectors() {
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(BackupCrypto.pbkdf2Sha256("password".toByteArray(), "salt".toByteArray(), 1, 32)))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(BackupCrypto.pbkdf2Sha256("password".toByteArray(), "salt".toByteArray(), 4096, 32)))
    }

    @Test fun roundTrip() {
        val data = ByteArray(10_000) { (it * 7).toByte() }
        val enc = BackupCrypto.encrypt(data, "correct horse", iterations = 1_000)
        assertTrue(BackupCrypto.isEncrypted(enc))
        assertFalse(BackupCrypto.isEncrypted(data))
        assertArrayEquals(data, BackupCrypto.decrypt(enc, "correct horse"))
    }

    @Test fun wrongPassphraseFails() {
        val enc = BackupCrypto.encrypt("secret".toByteArray(), "right", iterations = 1_000)
        assertThrows(WrongPassphraseException::class.java) { BackupCrypto.decrypt(enc, "wrong") }
    }

    @Test fun tamperingIsDetected() {
        val enc = BackupCrypto.encrypt("secret".toByteArray(), "pw", iterations = 1_000)
        val flippedBody = enc.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val flippedHeader = enc.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }   // iteration count
        assertThrows(WrongPassphraseException::class.java) { BackupCrypto.decrypt(flippedBody, "pw") }
        assertThrows(WrongPassphraseException::class.java) { BackupCrypto.decrypt(flippedHeader, "pw") }
    }

    @Test fun eachEncryptionUsesFreshSaltAndIv() {
        val a = BackupCrypto.encrypt("x".toByteArray(), "pw", iterations = 1_000)
        val b = BackupCrypto.encrypt("x".toByteArray(), "pw", iterations = 1_000)
        assertFalse(a.contentEquals(b))
    }
}
