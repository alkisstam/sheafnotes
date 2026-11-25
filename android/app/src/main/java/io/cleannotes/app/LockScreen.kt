package io.cleannotes.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Asks for a password, as the web's password dialog. `onSubmit` gets the password and, with `current`, the current one;
 * it returns an error to show, or null to close.
 */
@Composable
fun PasswordDialog(
    title: String,
    body: String?,
    confirm: String,
    repeat: Boolean = false,
    current: Boolean = false,
    onSubmit: suspend (password: String, current: String) -> String?,
    onDismiss: () -> Unit,
) {
    var old by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mismatch = repeat && again.isNotEmpty() && pw != again
    val ready = pw.isNotEmpty() && (!repeat || pw == again) && (!current || old.isNotEmpty()) && !busy
    val field = @Composable { value: String, label: String, set: (String) -> Unit ->
        OutlinedTextField(
            value, set, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (current) field(old, "Current password") { old = it }
                field(pw, if (current) "New password" else "Password") { pw = it }
                if (repeat) field(again, "Repeat password") { again = it }
                (error ?: if (mismatch) "The passwords do not match." else null)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                busy = true
                error = null
                scope.launch {
                    val e = try { onSubmit(pw, old) } catch (x: Exception) { x.message ?: "That did not work." }
                    busy = false
                    if (e == null) onDismiss() else error = e
                }
            }) { Text(if (busy) "Working…" else confirm) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The dialog that fits: choose a first password, or unlock. `onDone` gets whether locked notes are now open. */
@Composable
fun UnlockDialog(app: App, onDone: (Boolean) -> Unit) {
    var first by remember { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { first = !app.lock.hasPassword() }
    val isFirst = first ?: return
    PasswordDialog(
        title = if (isFirst) "Set a password for locked notes" else "Unlock locked notes",
        body = if (isFirst) {
            "One password locks all your notes. Titles, tags and notebooks stay visible. If you forget this password, locked notes cannot be recovered."
        } else if (app.lock.localVault() == null) "These notes were locked on another device. Enter the password used there." else null,
        confirm = if (isFirst) "Set password" else "Unlock",
        repeat = isFirst,
        onSubmit = { pw, _ ->
            try {
                if (isFirst) app.lock.setPassword(pw) else app.lock.unlock(pw)
                onDone(true)
                null
            } catch (e: Crypto.WrongPassword) {
                "That password is not right."
            }
        },
        onDismiss = { if (!app.lock.unlocked.value) onDone(false) },
    )
}

/** Shown in place of the editor while a locked note cannot be opened. */
@Composable
fun LockedPanel(app: App, modifier: Modifier = Modifier) {
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("This note is locked", style = MaterialTheme.typography.titleMedium)
        Text("Enter your password to read and edit it.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            pw, { pw = it }, Modifier.widthIn(max = 360.dp).fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(enabled = pw.isNotEmpty() && !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                error = try {
                    app.lock.unlock(pw)
                    null
                } catch (e: Crypto.WrongPassword) {
                    "That password is not right."
                } catch (e: Exception) {
                    e.message
                }
                busy = false
            }
        }) { Text(if (busy) "Unlocking…" else "Unlock") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
