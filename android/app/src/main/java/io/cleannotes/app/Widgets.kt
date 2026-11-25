package io.cleannotes.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment as GAlignment
import androidx.glance.layout.Column as GColumn
import androidx.glance.layout.Row as GRow
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize as gFillMaxSize
import androidx.glance.layout.fillMaxWidth as gFillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding as gPadding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text as GText
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields

/** Actions the widgets send to MainActivity, besides Reminders.OPEN_NOTE. */
object WidgetActions {
    const val NEW_NOTE = "io.cleannotes.app.NEW_NOTE"
    const val NEW_REMINDER = "io.cleannotes.app.NEW_REMINDER"
    const val EDIT_REMINDER = "io.cleannotes.app.EDIT_REMINDER"
}

private val PICKED = stringSetPreferencesKey("notes")
private val WidgetColors = ColorProviders(light = Day, dark = Night)

// Each row's intent gets its own data, or the launcher would merge their PendingIntents into one.
private fun open(ctx: Context, action: String, vararg extras: Pair<String, String?>): Action =
    actionStartActivity(Intent(ctx, MainActivity::class.java).setAction(action).setData("sheaf://${extras.joinToString("/") { it.second.orEmpty() }}".toUri())
        .putExtra("widget", true).apply { extras.forEach { (k, v) -> putExtra(k, v) } })

private fun openNote(ctx: Context, id: String) = open(ctx, Reminders.OPEN_NOTE, "noteId" to id)

suspend fun updateWidgets(ctx: Context) {
    TasksWidget().updateAll(ctx)
    RemindersWidget().updateAll(ctx)
    NotesWidget().updateAll(ctx)
}

/** Title top left, an optional button top right, and a scrolling list below. */
@Composable
private fun WidgetFrame(title: String, icon: Int?, iconLabel: String, onIcon: Action?, empty: String, rows: List<Any>, row: @Composable (Any) -> Unit) =
    GlanceTheme(WidgetColors) {
        GColumn(GlanceModifier.gFillMaxSize().background(GlanceTheme.colors.background).cornerRadius(24.dp).gPadding(start = 16.dp, end = 8.dp, top = 8.dp)) {
            GRow(GlanceModifier.gFillMaxWidth().height(48.dp), verticalAlignment = GAlignment.CenterVertically) {
                GText(title, GlanceModifier.defaultWeight(), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                if (icon != null && onIcon != null) Image(
                    ImageProvider(icon), iconLabel,
                    GlanceModifier.size(40.dp).cornerRadius(20.dp).background(GlanceTheme.colors.secondaryContainer).gPadding(10.dp).clickable(onIcon),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                )
            }
            if (rows.isEmpty()) GText(empty, GlanceModifier.gPadding(top = 8.dp), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
            else LazyColumn(GlanceModifier.gFillMaxSize().gPadding(end = 8.dp)) {
                items(rows) { row(it) }
            }
        }
    }

@Composable
private fun Header(label: String) = GText(
    label, GlanceModifier.gPadding(top = 8.dp, bottom = 4.dp),
    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
private fun Card(onClick: Action, color: String?, content: @Composable () -> Unit) = GColumn(GlanceModifier.gFillMaxWidth().gPadding(bottom = 6.dp)) {
    val bg = noteColor(color, false)?.let { ColorProvider(it, noteColor(color, true)!!) } ?: ColorProvider(Day.surfaceContainer, Night.surfaceContainer)
    GColumn(GlanceModifier.gFillMaxWidth().cornerRadius(16.dp).background(bg).clickable(onClick).gPadding(horizontal = 12.dp, vertical = 10.dp)) { content() }
}

private fun liveNotes(notes: List<NoteRow>) = notes.filter { it.trashedAt == null && it.archivedAt == null }

private fun color(row: NoteRow) = JSONObject(row.raw).optString("color").ifEmpty { null }

class TasksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as App
        val first = app.db.dao().allNotes()
        provideContent {
            val notes by app.db.dao().liveNotes().collectAsState(first)
            val g = groupTasks(liveNotes(notes).flatMap { extractTasks(it.id, it.title, JSONObject(it.raw).getJSONObject("content")) }, dateKey())
            val rows = listOf("Overdue" to g.overdue, "Today" to g.today, "Upcoming" to g.upcoming, "No date" to g.someday)
                .filter { it.second.isNotEmpty() }.flatMap { (k, v) -> listOf(k) + v }
            WidgetFrame("Tasks", null, "", null, "No open tasks", rows) { r ->
                if (r is String) Header(r) else {
                    val t = r as TaskRef
                    GRow(GlanceModifier.gFillMaxWidth(), verticalAlignment = GAlignment.CenterVertically) {
                        CheckBox(false, actionRunCallback<TickTask>(actionParametersOf(TickTask.NOTE to t.noteId, TickTask.TASK to t.id)))
                        GText(
                            t.text.ifBlank { "Untitled" }, GlanceModifier.defaultWeight().clickable(openNote(context, t.noteId)).gPadding(vertical = 8.dp),
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp), maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

class TickTask : ActionCallback {
    companion object {
        val NOTE = ActionParameters.Key<String>("note")
        val TASK = ActionParameters.Key<String>("task")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as App
        app.setTaskAttrs(parameters[NOTE] ?: return, parameters[TASK] ?: return, mapOf("checked" to true))
        TasksWidget().updateAll(context)
    }
}

class RemindersWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as App
        val first = app.db.dao().allNotes()
        provideContent {
            val notes by app.db.dao().liveNotes().collectAsState(first)
            val today = LocalDate.now()
            val g = groupReminders(reminderItems(notes), today)
            val rows = (listOf("Today" to g.today) + scheduledSections(g.scheduled, today, WeekFields.of(context.resources.configuration.locales[0]).firstDayOfWeek))
                .filter { it.second.isNotEmpty() }.flatMap { (k, v) -> listOf(k) + v }
            val colors = notes.associate { it.id to color(it) }
            WidgetFrame("Reminders", R.drawable.ic_plus, "New reminder", open(context, WidgetActions.NEW_REMINDER), "No reminders", rows) { r ->
                if (r is String) Header(r) else {
                    val item = r as ReminderItem
                    // As in the app: a note's own old-style reminder opens the note, others their editor.
                    val tap = if (item.kind != "reminder" && item.taskId == null) openNote(context, item.noteId)
                    else open(context, WidgetActions.EDIT_REMINDER, "noteId" to item.noteId, "taskId" to item.taskId)
                    Card(tap, colors[item.noteId]) {
                        GText(item.title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 2)
                        GText(
                            "${dueLabel(item.at.toLocalDate().toString())} ${item.at.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        )
                    }
                }
            }
        }
    }
}

class NotesWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as App
        val first = app.db.dao().allNotes()
        provideContent {
            val notes by app.db.dao().liveNotes().collectAsState(first)
            val picked = currentState<Preferences>()[PICKED].orEmpty()
            val rows = liveNotes(notes).filter { it.id in picked }.sortedByDescending { it.updatedAt }
            WidgetFrame("Notes", R.drawable.ic_square_pen, "New note", open(context, WidgetActions.NEW_NOTE), "No notes picked", rows) { r ->
                val n = r as NoteRow
                Card(openNote(context, n.id), color(n)) {
                    GText(n.title.ifBlank { "Untitled" }, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    if (n.text.isNotBlank()) GText(n.text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 3)
                }
            }
        }
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TasksWidget()
}

class RemindersWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = RemindersWidget()
}

class NotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NotesWidget()
}

// The same widgets again at other default sizes, so the picker lists each size.
class TasksWidgetReceiver2x2 : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TasksWidget()
}

class TasksWidgetReceiver4x2 : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TasksWidget()
}

class RemindersWidgetReceiver2x2 : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = RemindersWidget()
}

class RemindersWidgetReceiver4x2 : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = RemindersWidget()
}

class NotesWidgetReceiver2x2 : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NotesWidget()
}

class NotesWidgetReceiver4x2 : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NotesWidget()
}

/** Opens when a Notes widget is added or reconfigured: every note as a grid card, to pick which the widget shows. */
class WidgetPickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widget = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out without Done must not leave an empty widget behind.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget))
        if (widget == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val app = application as App
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(widget)
        enableEdgeToEdge()
        setContent {
            CleanTheme(app) {
                NotePicker(app, glanceId) { ids ->
                    app.scope.launch {
                        updateAppWidgetState(app, glanceId) { it[PICKED] = ids }
                        NotesWidget().update(app, glanceId)
                    }
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget))
                    finish()
                }
            }
        }
    }

    @Composable
    private fun NotePicker(app: App, glanceId: GlanceId, onDone: (Set<String>) -> Unit) {
        val all by app.db.dao().liveNotes().collectAsState(emptyList())
        val notes = listFor("all", all.filter { it.trashedAt == null })
        var picked by remember { mutableStateOf<Set<String>?>(null) }
        LaunchedEffect(Unit) { picked = getAppWidgetState(app, PreferencesGlanceStateDefinition, glanceId)[PICKED].orEmpty() }
        val dark = LocalDark.current
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(picked?.let { if (it.isEmpty()) "Choose notes" else "${it.size} selected" } ?: "Choose notes", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { finish() }) { Text("Cancel") }
                    TextButton(onClick = { onDone(picked.orEmpty()) }, enabled = picked != null) { Text("Done") }
                }
                LazyVerticalGrid(
                    GridCells.Fixed(2), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    items(notes, key = { it.id }) { note ->
                        val on = note.id in picked.orEmpty()
                        val bg = noteColor(color(note), dark)
                        Box {
                            Surface(
                                onClick = { picked = picked.orEmpty().let { if (on) it - note.id else it + note.id } },
                                shape = RoundedCornerShape(16.dp), color = bg ?: MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp).padding(end = 26.dp)) {
                                    Text(note.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        note.text.take(400).ifBlank { "No content yet" }, Modifier.padding(top = 4.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (bg != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        minLines = 6, maxLines = 6, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Tick(on)
                        }
                    }
                }
            }
        }
    }
}
