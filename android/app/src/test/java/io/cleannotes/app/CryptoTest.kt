package io.cleannotes.app

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class CryptoTest {
    // Made by the web app; the web's tests/crypto.test.ts opens the same file. Unit tests run in android/app.
    private val vectors = JSONObject(File("../../tests/fixtures/crypto-vectors.json").readText())
    private val password = vectors.getString("password")

    private fun decrypt(bytes: ByteArray, pw: String) = Crypto.DecryptingInputStream(ByteArrayInputStream(bytes), pw).readBytes()

    private fun encrypt(plain: ByteArray, pw: String, chunk: Int) = ByteArrayOutputStream().also { out ->
        Crypto.EncryptingOutputStream(out, pw, iterations = 1000, chunk = chunk).use { it.write(plain) }
    }.toByteArray()

    @Test fun pbkdf2MatchesTheStandardVector() {
        // RFC 7914 section 11, PBKDF2-HMAC-SHA256 with P="passwd", S="salt", c=1.
        val key = Crypto.deriveKey("passwd", "salt".toByteArray(), 1)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", key.joinToString("") { "%02x".format(it) })
    }

    @Test fun opensTheWebsLockedNote() {
        val key = Crypto.unlockVault(vectors.getJSONObject("vault"), password)
        val note = vectors.getJSONObject("note")
        val plain = JSONObject(Crypto.openJson(key, note.getJSONObject("locked"), note.getString("id")))
        assertEquals(note.getJSONObject("plain").getString("text"), plain.getString("text"))
        assertThrows(Crypto.WrongPassword::class.java) { Crypto.openJson(key, note.getJSONObject("locked"), "another-note") }
        assertThrows(Crypto.WrongPassword::class.java) { Crypto.unlockVault(vectors.getJSONObject("vault"), "wrong") }
    }

    @Test fun opensTheWebsBackup() {
        val file = Crypto.unb64(vectors.getJSONObject("backup").getString("file"))
        assertTrue(Crypto.isEncryptedBackup(file))
        assertEquals(vectors.getJSONObject("backup").getString("plain"), String(decrypt(file, password)))
        // Typed decomposed, the password still matches.
        assertEquals(vectors.getJSONObject("backup").getString("plain"), String(decrypt(file, java.text.Normalizer.normalize(password, java.text.Normalizer.Form.NFD))))
        assertThrows(Crypto.WrongPassword::class.java) { decrypt(file, "wrong") }
    }

    @Test fun vaultKeepsItsKeyAcrossPasswordChanges() {
        val (header, key) = Crypto.createVault("one", 1000)
        val next = Crypto.rewrapVault(header, "one", "two", 1000)
        assertEquals(header.getString("id"), next.getString("id"))
        assertArrayEquals(key, Crypto.unlockVault(next, "two"))
        assertThrows(Crypto.WrongPassword::class.java) { Crypto.unlockVault(next, "one") }
    }

    @Test fun backupStreamsRoundTripAndRefuseChanges() {
        for (size in listOf(0, 5, 10, 35)) {
            val plain = ByteArray(size) { it.toByte() }
            assertArrayEquals(plain, decrypt(encrypt(plain, "pw", 10), "pw"))
        }
        val sealed = encrypt(ByteArray(35) { 7 }, "pw", 10)
        val flipped = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(Crypto.WrongPassword::class.java) { decrypt(flipped, "pw") }
        // Chunks are 4 + 10 + 16 bytes, the last 4 + 5 + 16: drop it, or add another.
        assertThrows(Crypto.WrongPassword::class.java) { decrypt(sealed.copyOf(sealed.size - 25), "pw") }
        assertThrows(Crypto.WrongPassword::class.java) { decrypt(sealed + sealed.copyOfRange(sealed.size - 25, sealed.size), "pw") }
        assertThrows(Crypto.WrongPassword::class.java) { decrypt(sealed, "other") }
    }
}
