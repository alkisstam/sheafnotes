package io.cleannotes.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupCryptoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).allowMainThreadQueries().build()
    private val sync = Sync(context, db)
    private val backup = Backup(context, db, sync)

    @After fun close() = db.close()

    @Test fun encryptedBackupRoundTrips() = runBlocking {
        val note = JSONObject().put("id", "n1").put("title", "Secret title").put("updatedAt", 10).put("tags", JSONArray())
            .put("content", JSONObject().put("type", "doc").put("content", JSONArray())).put("text", "")
        sync.merge(listOf(note), emptyList(), emptyList(), emptyList())
        val file = File(context.cacheDir, "b.sheafenc")
        val uri = Uri.fromFile(file)
        backup.export(uri, "pw")
        assertFalse(String(file.readBytes()).contains("Secret title"))
        assertTrue(backup.isEncrypted(uri))

        db.clearAllTables()
        assertThrows(Backup.NeedsPassword::class.java) { runBlocking { backup.import(uri) } }
        assertThrows(Crypto.WrongPassword::class.java) { runBlocking { backup.import(uri, "nope") } }
        assertEquals(1, backup.import(uri, "pw").added)
        assertEquals("Secret title", db.dao().noteOnce("n1")!!.title)
    }

    @Test fun opensTheWebsEncryptedBackup() = runBlocking {
        val vectors = JSONObject(File("../../tests/fixtures/crypto-vectors.json").readText())
        val file = File(context.cacheDir, "web.sheafenc").apply { writeBytes(Crypto.unb64(vectors.getJSONObject("backup").getString("file"))) }
        assertEquals(0, backup.import(Uri.fromFile(file), vectors.getString("password")).added)
    }
}
