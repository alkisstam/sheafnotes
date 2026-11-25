package io.cleannotes.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Whether a note's content is encrypted. Cheap enough for lists: org.json writes the key exactly so, and escapes it inside strings. */
val NoteRow.isLocked get() = "\"locked\":{" in raw

/** Ids of the PDFs and images a note uses, the same way the rest of the app finds them: by their ids in the note's JSON. */
private fun fileIdsIn(content: JSONObject): JSONArray {
    val ids = linkedSetOf<String>()
    fun walk(n: JSONObject) {
        val attrs = n.optJSONObject("attrs")
        when (n.optString("type")) {
            "pdfAttachment" -> attrs?.optString("fileId")?.takeIf { it.isNotEmpty() }?.let(ids::add)
            "noteImage" -> attrs?.optString("imageId")?.takeIf { it.isNotEmpty() }?.let(ids::add)
        }
        val kids = n.optJSONArray("content") ?: return
        for (i in 0 until kids.length()) kids.optJSONObject(i)?.let(::walk)
    }
    walk(content)
    return JSONArray(ids.toList())
}

/**
 * Locked notes, as src/lib/lock.ts on the web: one app password wraps a random data key, and each locked note carries
 * a copy of that wrapped key, so any device with the password can open it. Titles, tags and notebooks stay readable;
 * the content and text are replaced by an empty document and an empty string, so lists, search and Tasks see nothing.
 */
class Lock(private val app: App) {
    private val prefs = app.getSharedPreferences("lock", Context.MODE_PRIVATE)
    /** Data keys of the vaults unlocked since the app started, by vault id. Memory only. */
    private val keys = ConcurrentHashMap<String, ByteArray>()
    val unlocked = MutableStateFlow(false)
    private var stoppedAt = 0L

    var lockAfterMinutes: Int
        get() = prefs.getInt("lockAfter", 5)
        set(value) = prefs.edit { putInt("lockAfter", value) }

    fun localVault(): JSONObject? = prefs.getString("vault", null)?.let(::JSONObject)
    private fun setLocalVault(v: JSONObject) = prefs.edit { putString("vault", v.toString()) }

    private suspend fun knownVaults(): List<JSONObject> {
        val out = linkedMapOf<String, JSONObject>()
        localVault()?.let { out[it.getString("id")] = it }
        app.db.dao().allNotes().filter { it.isLocked }.forEach { row ->
            val v = JSONObject(row.raw).getJSONObject("locked").getJSONObject("vault")
            out.putIfAbsent(v.getString("id"), v)
        }
        return out.values.toList()
    }

    suspend fun hasPassword() = localVault() != null || knownVaults().isNotEmpty()

    /** First password on this device. If notes locked elsewhere exist, it must be theirs. */
    suspend fun setPassword(password: String) = withContext(Dispatchers.Default) {
        check(localVault() == null) { "A password is already set." }
        if (knownVaults().isNotEmpty()) return@withContext unlock(password)
        val (header, key) = Crypto.createVault(password)
        setLocalVault(header)
        keys[header.getString("id")] = key
        unlocked.value = true
    }

    /** Opens every vault the password fits. Throws WrongPassword if it fits none. */
    suspend fun unlock(password: String) = withContext(Dispatchers.Default) {
        var opened = 0
        for (v in knownVaults()) {
            val id = v.getString("id")
            if (keys.containsKey(id)) { opened++; continue }
            try {
                keys[id] = Crypto.unlockVault(v, password)
                opened++
                if (localVault() == null) setLocalVault(v)
            } catch (_: Crypto.WrongPassword) {
            }
        }
        if (opened == 0) throw Crypto.WrongPassword()
        unlocked.value = true
    }

    /** The data key stays, so locked notes need no re-encrypting; only their copy of the vault header changes. */
    suspend fun changePassword(oldPassword: String, newPassword: String) = withContext(Dispatchers.Default) {
        val header = localVault() ?: error("No password is set.")
        val id = header.getString("id")
        val next = Crypto.rewrapVault(header, oldPassword, newPassword)
        keys.putIfAbsent(id, Crypto.unlockVault(next, newPassword))
        setLocalVault(next)
        app.db.dao().allNotes().filter { it.isLocked && JSONObject(it.raw).getJSONObject("locked").getJSONObject("vault").getString("id") == id }
            .forEach { row -> app.updateNote(row.id) { j, _ -> j.getJSONObject("locked").put("vault", next) } }
        unlocked.value = true
    }

    /** Whether a note can be locked right away, without asking for the password. */
    fun canLock() = localVault()?.let { keys.containsKey(it.getString("id")) } == true

    fun lockNow() {
        keys.clear()
        unlocked.value = false
    }

    fun onStop() {
        stoppedAt = System.currentTimeMillis()
    }

    /** Locks again if the app was away longer than the chosen time. 0 means only on Lock now or a restart. */
    fun onStart() {
        val after = lockAfterMinutes
        if (after > 0 && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > after * 60_000L) lockNow()
        stoppedAt = 0
    }

    private fun keyFor(note: JSONObject): ByteArray {
        val id = note.getJSONObject("locked").getJSONObject("vault").getString("id")
        return keys[id] ?: throw IllegalStateException("Unlock your locked notes first.")
    }

    fun isOpenable(row: NoteRow) = !row.isLocked || keys.containsKey(JSONObject(row.raw).getJSONObject("locked").getJSONObject("vault").getString("id"))

    /** The note as it reads: content and text decrypted, the lock fields left out. */
    fun open(note: JSONObject): JSONObject {
        val locked = note.optJSONObject("locked") ?: return note
        val plain = JSONObject(Crypto.openJson(keyFor(note), locked, note.getString("id")))
        return JSONObject(note.toString()).put("content", plain.getJSONObject("content")).put("text", plain.optString("text"))
            .apply { remove("locked"); remove("fileIds") }
    }

    /** Encrypts a note's content and text in place, under the given vault and key. */
    private fun seal(note: JSONObject, vault: JSONObject, key: ByteArray): JSONObject {
        val content = note.getJSONObject("content")
        val text = docText(content)
        val sealed = Crypto.sealJson(key, JSONObject().put("content", content).put("text", text).toString(), note.getString("id"))
        return note.put("locked", sealed.put("v", 1).put("vault", vault)).put("fileIds", fileIdsIn(content))
            .put("content", JSONObject().put("type", "doc").put("content", JSONArray())).put("text", "")
    }

    /** An editor save for a note that is locked here: encrypted with the lock it already has. */
    fun resealEdit(edit: JSONObject, stored: NoteRow): JSONObject {
        val vault = JSONObject(stored.raw).getJSONObject("locked").getJSONObject("vault")
        val key = keys[vault.getString("id")] ?: throw IllegalStateException("The note was locked before this edit was saved.")
        return seal(edit.apply { remove("locked"); remove("fileIds") }, vault, key)
    }

    suspend fun lockNote(id: String) {
        val vault = localVault() ?: error("Set a password first.")
        val key = keys[vault.getString("id")] ?: error("Unlock your locked notes first.")
        app.updateNote(id, contentChanged = true) { j, _ ->
            if (j.optJSONObject("locked") == null && j.optString("kind", "note") == "note") seal(j, vault, key)
        }
    }

    suspend fun removeLock(id: String) {
        val row = app.db.dao().noteOnce(id) ?: return
        if (!row.isLocked) return
        app.updateNote(id, contentChanged = true) { j, _ ->
            val plain = open(j)
            j.put("content", plain.getJSONObject("content")).put("text", plain.optString("text"))
            j.put("locked", JSONObject.NULL).put("fileIds", JSONObject.NULL)
        }
    }
}
