package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.formatElapsed
import com.singularity.todo.core.ui.monthAbbreviation
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.LogbookEntry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Task logbook — a chronological feed of notes and time entries attached to the current task.
 *
 * Rendered inside [TaskEditorContent] extraSections. Groups entries by day and displays them
 * in reverse-chronological order.
 *
 * @param entries The logbook entries to display (already extracted from [TaskLogbookState]).
 * @param onOpenNote Invoked when the user taps a note — opens the note preview.
 * @param onAddNote Invoked when the user taps the add note button — opens the note editor
 *                  pre-attached to the current task.
 * @param currentTaskId The task to attach new notes to.
 */
@Composable
fun LogbookSection(
    entries: List<LogbookEntry>,
    onOpenNote: (NoteId) -> Unit,
    onAddNote: (TaskId) -> Unit,
    currentTaskId: TaskId,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        LogbookEmptyCard(
            onAddNote = { onAddNote(currentTaskId) },
            modifier = modifier,
        )
    } else {
        LogbookLoadedCard(
            entries = entries,
            onOpenNote = onOpenNote,
            onAddNote = { onAddNote(currentTaskId) },
            modifier = modifier,
        )
    }
}

@Composable
private fun LogbookEmptyCard(onAddNote: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = TaskColors.Surface,
        shape = RoundedCornerShape(TaskSpacing.cardCornerRadius),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = TaskSpacing.cardPaddingHorizontal,
                vertical = TaskSpacing.cardPaddingVertical,
            )
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.TextSnippet,
                contentDescription = null,
                tint = TaskColors.TextSecondary,
                modifier = Modifier.size(TaskSpacing.iconSize),
            )
            Spacer(Modifier.width(TaskSpacing.lg))
            Text(
                text = "Logbook",
                color = TaskColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAddNote) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add note to logbook",
                    tint = TaskColors.TextSecondary,
                    modifier = Modifier.size(TaskSpacing.iconSize),
                )
            }
        }
    }
}

@Composable
private fun LogbookLoadedCard(
    entries: List<LogbookEntry>,
    onOpenNote: (NoteId) -> Unit,
    onAddNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = remember(entries) {
        entries.groupBy { entry ->
            val instant = when (entry) {
                is LogbookEntry.NoteEntry -> entry.note.createdAt
                is LogbookEntry.TimeEntryRow -> entry.entry.startedAt
            }
            instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
        }
    }

    Surface(
        color = TaskColors.Surface,
        shape = RoundedCornerShape(TaskSpacing.cardCornerRadius),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = TaskSpacing.cardPaddingHorizontal,
                vertical = TaskSpacing.cardPaddingVertical,
            )
                .fillMaxWidth(),
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TextSnippet,
                    contentDescription = null,
                    tint = TaskColors.TextSecondary,
                    modifier = Modifier.size(TaskSpacing.iconSize),
                )
                Spacer(Modifier.width(TaskSpacing.lg))
                Text(
                    text = "Logbook (${entries.size})",
                    color = TaskColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAddNote) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add note to logbook",
                        tint = TaskColors.TextSecondary,
                        modifier = Modifier.size(TaskSpacing.iconSize),
                    )
                }
            }

            Spacer(Modifier.height(TaskSpacing.md))

            // Grouped entries
            grouped.forEach { (date: LocalDate, dayEntries: List<LogbookEntry>) ->
                Text(
                    text = formatDayLabel(date),
                    color = TaskColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 4.dp, top = 8.dp),
                )
                dayEntries.forEach { entry: LogbookEntry ->
                    when (entry) {
                        is LogbookEntry.NoteEntry -> LogbookNoteRow(
                            note = entry.note,
                            onClick = { onOpenNote(entry.note.id) },
                        )

                        is LogbookEntry.TimeEntryRow -> LogbookTimeEntryRow(
                            entry = entry.entry,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogbookNoteRow(note: Note, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = note.title.ifBlank { "(untitled)" },
            color = TaskColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LogbookTimeEntryRow(
    entry: com.singularity.todo.feature.timetracking.domain.TimeEntry,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = null,
            tint = TaskColors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        val duration = entry.durationMs?.let { formatElapsed(it) } ?: "running"
        Text(
            text = "${entry.kind.name.lowercase()} · $duration",
            color = TaskColors.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Formats a [LocalDate] as a human-readable day label.
 * "Today", "Yesterday", or "Jan 1" for other days.
 */
private fun formatDayLabel(date: LocalDate): String {
    val nowMs = kotlin.time.Clock.System.now()
        .toEpochMilliseconds()
    val today = LocalDate.fromEpochDays((nowMs / 86_400_000).toInt())
    val yesterday = LocalDate.fromEpochDays(today.toEpochDays() - 1)
    return when (date) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> "${monthAbbreviation(date.month)} ${date.day}"
    }
}
