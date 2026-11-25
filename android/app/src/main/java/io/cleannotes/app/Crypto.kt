package io.cleannotes.app

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The web app's src/lib/crypto.ts, byte for byte, so either side opens the other's locked notes and encrypted backups.
 * PBKDF2-HMAC-SHA256 turns a password (NFC, UTF-8) into an AES-256-GCM key. Only java.* and org.json, so it tests on the plain JVM.
 */
object Crypto {
    const val ITERATIONS = 600_000
    const val CHUNK = 1024 * 1024

    class WrongPassword(message: String = "Wrong password.") : Exception(message)

    private val rng = SecureRandom()
    fun random(n: Int) = ByteArray(n).also(rng::nextBytes)
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    /**
     * Written out rather than through SecretKeyFactory: Android's providers have differed in how they turn a char[]
     * password into bytes, and this must match WebCrypto exactly. One block, since the key is exactly one SHA-256 long.
     */
    fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        val u = mac.doFinal()
        val out = u.copyOf()
        repeat(iterations - 1) {
            mac.update(u)
            mac.doFinal(u, 0)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray?) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) updateAAD(aad)
    }

    fun seal(key: ByteArray, iv: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, iv, aad).doFinal(plain)

    /** Throws WrongPassword when the key is wrong or the bytes were changed; GCM cannot tell the two apart. */
    fun open(key: ByteArray, iv: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, key, iv, aad).doFinal(sealed)
    } catch (e: AEADBadTagException) {
        throw WrongPassword()
    } catch (e: IllegalArgumentException) {
        throw WrongPassword()
    }

    // ---- The vault: one random data key for locked notes, wrapped by the app password. ----

    private fun vaultAad(id: String) = "sheaf-vault:$id".toByteArray()

    private fun wrap(id: String, raw: ByteArray, password: String, iterations: Int): JSONObject {
        val salt = random(16)
        val iv = random(12)
        val key = seal(deriveKey(password, salt, iterations), iv, raw, vaultAad(id))
        return JSONObject().put("id", id).put("salt", b64(salt)).put("iterations", iterations).put("iv", b64(iv)).put("key", b64(key))
    }

    /** A new vault header and its data key. */
    fun createVault(password: String, iterations: Int = ITERATIONS): Pair<JSONObject, ByteArray> {
        val raw = random(32)
        return wrap(UUID.randomUUID().toString(), raw, password, iterations) to raw
    }

    fun unlockVault(header: JSONObject, password: String): ByteArray {
        val kek = deriveKey(password, unb64(header.getString("salt")), header.getInt("iterations"))
        return open(kek, unb64(header.getString("iv")), unb64(header.getString("key")), vaultAad(header.getString("id")))
    }

    fun rewrapVault(header: JSONObject, oldPassword: String, newPassword: String, iterations: Int = ITERATIONS) =
        wrap(header.getString("id"), unlockVault(header, oldPassword), newPassword, iterations)

    /** Encrypts JSON text; the context (a note id) is bound in, so the ciphertext cannot be moved to another note. */
    fun sealJson(key: ByteArray, json: String, context: String): JSONObject {
        val iv = random(12)
        return JSONObject().put("iv", b64(iv)).put("data", b64(seal(key, iv, json.toByteArray(), context.toByteArray())))
    }

    fun openJson(key: ByteArray, sealed: JSONObject, context: String) =
        String(open(key, unb64(sealed.getString("iv")), unb64(sealed.getString("data")), context.toByteArray()))

    // ---- Encrypted backup files ----
    // "SHEAFENC", version byte 1, u32 header length, UTF-8 JSON header, then chunks of [u32 length, AES-GCM ciphertext].
    // Chunk i uses IV = nonce(8) || i (u32 big-endian) and AAD = header bytes || final flag, set on the last chunk only.

    private val MAGIC = "SHEAFENC".toByteArray()
    private const val DAMAGED = "Wrong password, or the file is damaged."

    private fun chunkIv(nonce: ByteArray, i: Int) = ByteBuffer.allocate(12).put(nonce).putInt(i).array()
    private fun chunkAad(header: ByteArray, final: Boolean) = header + byteArrayOf(if (final) 1 else 0)

    /** Whether these first bytes start an encrypted backup. */
    fun isEncryptedBackup(head: ByteArray) = head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    /** Whether a stream starts an encrypted backup. Reads up to 8 bytes; InputStream.readNBytes needs Android 13. */
    fun startsEncrypted(input: InputStream): Boolean {
        val head = ByteArray(MAGIC.size)
        var got = 0
        while (got < head.size) {
            val r = input.read(head, got, head.size - got)
            if (r < 0) break
            got += r
        }
        return got == head.size && isEncryptedBackup(head)
    }

    /** Streams an encrypted backup; nothing is written for good until close(), which writes the final chunk. */
    class EncryptingOutputStream(
        private val out: OutputStream,
        password: String,
        iterations: Int = ITERATIONS,
        private val chunk: Int = CHUNK,
    ) : OutputStream() {
        private val salt = random(16)
        private val nonce = random(8)
        private val key = deriveKey(password, salt, iterations)
        private val header = JSONObject()
            .put("kdf", "PBKDF2-SHA256").put("iterations", iterations).put("salt", b64(salt))
            .put("cipher", "AES-256-GCM").put("chunk", chunk).put("nonce", b64(nonce))
            .toString().toByteArray()
        private val buf = ByteArray(chunk)
        private var len = 0
        private var index = 0
        private var closed = false

        init {
            out.write(MAGIC)
            out.write(1)
            out.write(ByteBuffer.allocate(4).putInt(header.size).array())
            out.write(header)
        }

        private fun emit(final: Boolean) {
            val sealed = seal(key, chunkIv(nonce, index++), buf.copyOf(len), chunkAad(header, final))
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            len = 0
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, n: Int) {
            var at = off
            val end = off + n
            while (at < end) {
                // A full buffer is only sent once more data shows it is not the last chunk.
                if (len == chunk) emit(false)
                val take = minOf(chunk - len, end - at)
                System.arraycopy(b, at, buf, len, take)
                len += take
                at += take
            }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            emit(true)
            out.close()
        }
    }

    /** Reads the backup JSON out of an encrypted backup. Throws WrongPassword for a wrong password or changed bytes. */
    class DecryptingInputStream(private val input: InputStream, password: String) : InputStream() {
        private val header: ByteArray get() = headerBytes
        private lateinit var nonce: ByteArray
        private lateinit var key: ByteArray
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var next: ByteArray? = null
        private var done = false

        init {
            try {
                readHeaderInto(password)
            } catch (e: EOFException) {
                throw IllegalArgumentException(DAMAGED)
            }
        }

        private lateinit var headerBytes: ByteArray

        private fun readHeaderInto(password: String) {
            val magic = input.readExactly(MAGIC.size)
            if (!isEncryptedBackup(magic)) throw IllegalArgumentException("That file is not an encrypted backup.")
            if (input.read() != 1) throw IllegalArgumentException("That encrypted backup was made by a newer version of Sheaf Notes.")
            headerBytes = input.readExactly(ByteBuffer.wrap(input.readExactly(4)).int)
            val h = try { JSONObject(String(header)) } catch (e: Exception) { throw IllegalArgumentException(DAMAGED) }
            require(h.optString("kdf") == "PBKDF2-SHA256" && h.optString("cipher") == "AES-256-GCM") { DAMAGED }
            nonce = unb64(h.getString("nonce"))
            key = deriveKey(password, unb64(h.getString("salt")), h.getInt("iterations"))
            next = readChunk() ?: throw WrongPassword(DAMAGED)
        }

        private fun readChunk(): ByteArray? {
            val head = ByteArray(4)
            var got = 0
            while (got < 4) {
                val r = input.read(head, got, 4 - got)
                if (r < 0) break
                got += r
            }
            if (got == 0) return null
            if (got < 4) throw WrongPassword(DAMAGED)
            return try { input.readExactly(ByteBuffer.wrap(head).int) } catch (e: EOFException) { throw WrongPassword(DAMAGED) }
        }

        private fun advance(): Boolean {
            if (done) return false
            val sealed = next ?: return false
            next = readChunk()
            val final = next == null
            plain = try { open(key, chunkIv(nonce, index++), sealed, chunkAad(header, final)) } catch (e: WrongPassword) { throw WrongPassword(DAMAGED) }
            pos = 0
            if (final) done = true
            return true
        }

        override fun read(): Int {
            while (pos >= plain.size) if (!advance()) return -1
            return plain[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) if (!advance()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = input.close()
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        require(n in 0..(64 * 1024 * 1024)) { DAMAGED }
        val out = ByteArrayOutputStream(n)
        val buf = ByteArray(minOf(n, 64 * 1024).coerceAtLeast(1))
        var left = n
        while (left > 0) {
            val r = read(buf, 0, minOf(buf.size, left))
            if (r < 0) throw EOFException()
            out.write(buf, 0, r)
            left -= r
        }
        return out.toByteArray()
    }
}
