package io.cleannotes.app

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** The Daily notes page: the web's calendar on top, then the picked day's note and the reminders due that day. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DailyBody(
    app: App, all: List<NoteRow>, colors: Map<String, String>, dailyDay: MutableState<String>, onOpen: (String) -> Unit, onEdit: (String) -> Unit,
    onEditTask: (noteId: String, taskId: String) -> Unit, onRemindNote: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val exportNotes = rememberNoteExporter(app)
    val today = dateKey()
    var day by dailyDay
    val reminders = remember(all) { reminderItems(all) }
    val dayReminders = remember(reminders, day) { reminders.filter { it.at.toLocalDate().toString() == day }.sortedBy { it.at } }
    val marked = remember(all, reminders) {
        all.filter { it.kind == "daily" && it.trashedAt == null && it.text.isNotBlank() }.mapNotNull { it.date }.toSet() +
            reminders.filter { !it.done }.map { it.at.toLocalDate().toString() }
    }
    val note = all.firstOrNull { it.kind == "daily" && it.date == day && it.trashedAt == null }
    val from = LocalOpenFrom.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item { Calendar(today, day, marked) { day = it } }
        item(key = "note:$day") {
            var pressed by remember { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().noteBounds("${note?.id}|daily", 0.dp).background(MaterialTheme.colorScheme.background).combinedClickable(
                    onClick = {
                        if (note != null) { from.value = "${note.id}|daily"; onOpen(note.id) }
                        // Browsing days creates nothing; the note is made on the first tap.
                        else scope.launch { onOpen(app.dailyNote(day)) }
                    },
                    onLongClick = if (note != null) ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); pressed = true }) else null,
                ).padding(16.dp, 12.dp),
            ) {
                Text(note?.title?.ifBlank { null } ?: longDate(day), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    note?.text.orEmpty().replace('\n', ' ').take(170).ifBlank { "No content yet" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note != null) NoteMenu(
                    app, note, all, pressed, { pressed = false }, { it() }, {}, onRemindNote, centered = true,
                    onExport = { f -> exportNotes(listOf(note.id), f) },
                    onShare = { f -> shareNotes(app, context, listOf(note.id), f) },
                )
            }
            HorizontalDivider()
        }
        if (dayReminders.isNotEmpty()) item {
            Text(
                "Reminders", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(dayReminders, key = { "rem:${it.key}" }) { r ->
            var pressed by remember { mutableStateOf(false) }
            ReminderCard(
                app, r, colors[r.noteId], selecting = false, picked = false,
                modifier = Modifier.padding(12.dp, 4.dp).fillMaxWidth().animateItem().noteBounds("rem:${r.key}|daily", 16.dp),
                onClick = {
                    if (r.kind != "reminder" && r.taskId == null) onOpen(r.noteId)
                    else {
                        from.value = "rem:${r.key}|daily"
                        if (r.taskId != null) onEditTask(r.noteId, r.taskId) else onEdit(r.noteId)
                    }
                },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); pressed = true },
                onToggle = {},
                onOpen = onOpen,
            )
            if (pressed) ReminderMenu(app, r) { pressed = false }
        }
    }
}

/** Port of Calendar in ListColumn.tsx: the picked day boxed, today in the accent color, a dot on days with a note or reminder. */
@Composable
private fun Calendar(today: String, selected: String, withNotes: Set<String>, onPick: (String) -> Unit) {
    var month by rememberSaveable { mutableStateOf(today.take(7)) }
    val ym = YearMonth.parse(month)
    val weekStart = remember { firstDayOfWeek() }
    val grid = monthGrid(ym.year, ym.monthValue, weekStart)
    val locale = LocalConfiguration.current.locales[0]
    val title = ym.atDay(1).format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy")))
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(12.dp, 4.dp, 12.dp, 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).padding(start = 4.dp), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { month = ym.minusMonths(1).toString() }) { Icon(painterResource(R.drawable.ic_chevron_left), "Previous month", Modifier.size(18.dp)) }
            TextButton(onClick = { month = today.take(7); onPick(today) }) { Text("Today") }
            IconButton(onClick = { month = ym.plusMonths(1).toString() }) { Icon(painterResource(R.drawable.ic_chevron_right), "Next month", Modifier.size(18.dp)) }
        }
        Row {
            grid.take(7).forEach { key ->
                Text(
                    LocalDate.parse(key).dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        grid.chunked(7).forEach { week ->
            Row {
                week.forEach { key ->
                    val outside = key.take(7) != month
                    val isToday = key == today
                    val isPicked = key == selected
                    val has = key in withNotes
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).heightIn(max = 44.dp).padding(1.dp).clip(RoundedCornerShape(7.dp))
                            .then(if (isPicked) Modifier.background(colors.onSurface) else Modifier)
                            .clickable { onPick(key) }
                            .alpha(if (outside && !isPicked) 0.6f else 1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            LocalDate.parse(key).dayOfMonth.toString(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = if (isToday || isPicked) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isPicked -> colors.surface
                                isToday -> colors.primary
                                outside -> colors.onSurfaceVariant
                                else -> colors.onSurface
                            },
                        )
                        if (has) {
                            Box(
                                Modifier.align(Alignment.BottomCenter).offset(y = (-5).dp).size(4.dp).clip(CircleShape)
                                    .background(if (isPicked) colors.surface else colors.primary),
                            )
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider()
}
