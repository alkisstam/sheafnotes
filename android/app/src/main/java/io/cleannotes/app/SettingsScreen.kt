package io.cleannotes.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: App, onBack: () -> Unit, onConnect: () -> Unit) {
    val theme by app.theme.collectAsState()
    val webFonts by app.webFonts.collectAsState()
    val paired by app.sync.paired.collectAsState()
    val syncedAt by app.sync.syncedAt.collectAsState()
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf<String?>(null) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf("") }

    LaunchedEffect(busy) {
        storage = withContext(Dispatchers.IO) {
            val db = listOf("", "-wal", "-shm").sumOf { app.getDatabasePath("clean-notes$it").length() }
            val blobs = File(app.filesDir, "blobs").listFiles().orEmpty().sumOf { it.length() }
            "Notes ${formatBytes(db)} · PDFs and images ${formatBytes(blobs)}"
        }
    }
    val backupJob = { what: String, job: suspend () -> String ->
        busy = true
        backupNote = "$what…"
        scope.launch {
            backupNote = try {
                job()
            } catch (e: Throwable) {
                // OutOfMemoryError included: a huge file should fail with a message, not close the app.
                e.message ?: "That did not work."
            } finally {
                busy = false
            }
        }
    }
    // Held from the password dialog until the system's file dialog returns; memory only.
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var askExportPassword by remember { mutableStateOf(false) }
    var encryptedImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupJob("Exporting") { app.backup.export(uri); "Backup saved." }
    }
    val encryptedExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = exportPassword
        exportPassword = null
        if (uri != null && pw != null) backupJob("Exporting") { app.backup.export(uri, pw); "Encrypted backup saved." }
    }
    val summary = { r: Sync.MergeResult ->
        // Same summary as the web's import.
        if (r.added + r.updated + r.deleted == 0) "Nothing new: this device already has everything in that backup."
        else listOfNotNull(
            r.added.takeIf { it > 0 }?.let { "$it added" },
            r.updated.takeIf { it > 0 }?.let { "$it updated" },
            r.deleted.takeIf { it > 0 }?.let { "$it deleted" },
        ).joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            if (app.backup.isEncrypted(uri)) encryptedImport = uri
            else backupJob("Importing") { summary(app.backup.import(uri)) }
        }
    }
    var unlocking by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    val unlocked by app.lock.unlocked.collectAsState()
    var lockAfter by remember { mutableStateOf(app.lock.lockAfterMinutes) }
    var hasPassword by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(unlocked) { hasPassword = app.lock.hasPassword() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Section("Appearance") {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                    Choice(label, theme == key) { app.setTheme(key) }
                }
                Text("Fonts", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                Choice("Web fonts, as on the web app", webFonts) { app.setWebFonts(true) }
                Choice("This phone's fonts", !webFonts) { app.setWebFonts(false) }
            }
            Section("Sync") {
                if (paired) {
                    Text(app.sync.address().orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (syncedAt == 0L) "Not synced yet" else "Last synced ${ago(syncedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !syncing, onClick = {
                            syncing = true
                            syncNote = null
                            scope.launch {
                                syncNote = try {
                                    app.sync.run()
                                    null
                                } catch (e: Exception) {
                                    if (e is SyncError) e.message else "Sync failed. Check your connection."
                                } finally {
                                    syncing = false
                                }
                            }
                        }) { Text(if (syncing) "Syncing…" else "Sync now") }
                        OutlinedButton(onClick = { app.sync.unpair() }) { Text("Disconnect") }
                    }
                    syncNote?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } else {
                    Text("Notes stay on this phone. Connect to a Sheaf Notes server to keep your devices in step.")
                    Button(onClick = onConnect) { Text("Connect to server") }
                }
            }
            Section("Locked notes") {
                Text(
                    when (hasPassword) {
                        null -> ""
                        false -> "No password is set. Choose Lock note in a note's menu to set one."
                        else -> if (unlocked) "Unlocked for now." else "Locked."
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        hasPassword == false -> Button(onClick = { unlocking = true }) { Text("Set password") }
                        unlocked -> Button(onClick = { app.lock.lockNow() }) { Text("Lock now") }
                        else -> Button(onClick = { unlocking = true }) { Text("Unlock") }
                    }
                    if (hasPassword == true && app.lock.localVault() != null) OutlinedButton(onClick = { changing = true }) { Text("Change password") }
                }
                Text("Lock again after the app is away for", style = MaterialTheme.typography.labelLarge)
                listOf(1 to "1 minute", 5 to "5 minutes", 15 to "15 minutes", 60 to "1 hour", 0 to "Only when I lock or the app restarts").forEach { (min, label) ->
                    Choice(label, lockAfter == min) { app.lock.lockAfterMinutes = min; lockAfter = min }
                }
                Text(
                    "Locked notes are encrypted with AES-256. Their titles, tags and notebooks stay visible, and their PDFs and images are not encrypted. If you forget the password, locked notes cannot be recovered.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section("Backup") {
                Text("One file with every note, notebook, PDF and image, in the web app's backup format. Importing merges: the newer edit of each note wins.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { exporter.launch("sheaf-notes-backup-${dateKey()}.json") }) { Text("Export") }
                    OutlinedButton(enabled = !busy, onClick = { askExportPassword = true }) { Text("Export encrypted") }
                }
                OutlinedButton(enabled = !busy, onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) { Text("Import") }
                Text(
                    "Encrypted backups use AES-256 with a key made from your password. Without the password they cannot be restored.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                backupNote?.let { Text(it) }
            }
            Section("Storage") {
                Text(storage, style = MaterialTheme.typography.bodyMedium)
                val version = remember { app.packageManager.getPackageInfo(app.packageName, 0).versionName }
                Text("Sheaf Notes $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (unlocking) UnlockDialog(app) { unlocking = false }
    if (changing) PasswordDialog(
        title = "Change password",
        body = "Locked notes stay locked. Other devices use the new password once they sync.",
        confirm = "Change",
        repeat = true,
        current = true,
        onSubmit = { pw, cur ->
            try {
                app.lock.changePassword(cur, pw)
                null
            } catch (e: Crypto.WrongPassword) {
                "The current password is not right."
            }
        },
        onDismiss = { changing = false },
    )
    if (askExportPassword) PasswordDialog(
        title = "Export encrypted backup",
        body = "The backup is encrypted with AES-256, using a key made from this password. Keep the password safe: without it the backup cannot be restored.",
        confirm = "Continue",
        repeat = true,
        onSubmit = { pw, _ ->
            exportPassword = pw
            encryptedExporter.launch("sheaf-notes-backup-${dateKey()}.sheafenc")
            null
        },
        onDismiss = { askExportPassword = false },
    )
    encryptedImport?.let { uri ->
        PasswordDialog(
            title = "Restore encrypted backup",
            body = "Enter the password this backup was encrypted with.",
            confirm = "Restore",
            onSubmit = { pw, _ ->
                try {
                    val r = app.backup.import(uri, pw)
                    backupNote = summary(r)
                    null
                } catch (e: Crypto.WrongPassword) {
                    "Wrong password, or the file is damaged."
                }
            },
            onDismiss = { encryptedImport = null },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
    HorizontalDivider()
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
