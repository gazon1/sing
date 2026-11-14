package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.SectionHeader
import com.singularity.todo.core.ui.components.sectionTitle
import com.singularity.todo.core.ui.formatElapsed
import com.singularity.todo.core.ui.monthAbbreviation
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.viewmodel.slot.LogbookEntry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Task logbook — a chronological feed of notes and time entries attached to the current task.
 *
 * Rendered inside [TaskEditorContent] extraSections, after the attribute rows. Groups entries by
 * day and displays them in reverse-chronological order.
 *
 * @param entries The logbook entries to display (already extracted from [TaskLogbookState]).
 * @param onOpenNote Invoked when the user taps a note — opens the note preview.
 * @param onAddNote Invoked when the user taps the add note button — opens the note editor
 *                  pre-attached to the current task.
 * @param currentTaskId The task to attach new notes to.
 * @param expanded Whether the section is open. The header is the toggle.
 * @param onToggleExpanded Called when the header is tapped. `null` means the section
 *                  cannot be collapsed and its content is always drawn.
 */
@Composable
fun LogbookSection(
    entries: ImmutableList<LogbookEntry>,
    onOpenNote: (NoteId) -> Unit,
    onAddNote: (TaskId) -> Unit,
    currentTaskId: TaskId,
    now: kotlin.time.Instant,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpanded: (() -> Unit)? = null,
) {
    // Compute today/yesterday once from the injected clock (#91) so the "Today" /
    // "Yesterday" labels are stable and testable rather than derived from the host clock.
    val nowMs = now.toEpochMilliseconds()
    val today = LocalDate.fromEpochDays((nowMs / 86_400_000).toInt())
    val yesterday = LocalDate.fromEpochDays(today.toEpochDays() - 1)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(TaskSpacing.cardCornerRadius),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(
                        horizontal = TaskSpacing.cardPaddingHorizontal,
                        vertical = TaskSpacing.cardPaddingVertical,
                    ).fillMaxWidth(),
        ) {
            // The header carries the title, the count and the add-note action. The
            // action stays in the header while the section is closed: a note is
            // added from the header, not from the list under it.
            SectionHeader(
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TextSnippet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(TaskSpacing.iconSize),
                    )
                },
                label = sectionTitle("Logbook", entries.size),
                expanded = expanded,
                onToggle = onToggleExpanded,
                testTag = TestTags.LOGBOOK_SECTION,
                trailing = {
                    IconButton(
                        onClick = { onAddNote(currentTaskId) },
                        modifier = Modifier.testTag(TestTags.LOGBOOK_ADD_NOTE_BUTTON),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add note to logbook",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(TaskSpacing.iconSize),
                        )
                    }
                },
            )

            if (onToggleExpanded == null || expanded) {
                Spacer(Modifier.height(TaskSpacing.md))

                if (entries.isEmpty()) {
                    // An empty logbook says so, rather than leaving the card with a
                    // title and a button and nothing else.
                    Box(modifier = Modifier.testTag(TestTags.LOGBOOK_EMPTY)) {
                        EmptySectionHint(
                            text = "No notes or time entries yet. Add a note to start the log.",
                            modifier = Modifier.testTag(TestTags.LOGBOOK_EMPTY_HINT),
                        )
                    }
                } else {
                    LogbookDayGroups(
                        entries = entries,
                        today = today,
                        yesterday = yesterday,
                        onOpenNote = onOpenNote,
                    )
                }
            }
        }
    }
}

/**
 * The entries of the logbook, grouped by day.
 *
 * Extracted from the section so that the section reads as what it is: a header
 * and a body. The body is the same either way — only its size changes.
 */
@Composable
private fun LogbookDayGroups(
    entries: ImmutableList<LogbookEntry>,
    today: LocalDate,
    yesterday: LocalDate,
    onOpenNote: (NoteId) -> Unit,
) {
    // The entries are grouped by the day they happened on; inside a day they
    // keep the order they came in.
    val grouped =
        remember(entries) {
            entries.groupBy { entry ->
                val instant =
                    when (entry) {
                        is LogbookEntry.NoteEntry -> entry.note.createdAt
                        is LogbookEntry.TimeEntryRow -> entry.entry.startedAt
                    }
                instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            }
        }
    grouped.forEach { (date: LocalDate, dayEntries: List<LogbookEntry>) ->
        Text(
            text = formatDayLabel(date, today, yesterday),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp, top = 8.dp),
        )
        dayEntries.forEach { entry: LogbookEntry ->
            when (entry) {
                is LogbookEntry.NoteEntry -> {
                    LogbookNoteRow(
                        note = entry.note,
                        onClick = { onOpenNote(entry.note.id) },
                    )
                }

                is LogbookEntry.TimeEntryRow -> {
                    LogbookTimeEntryRow(
                        entry = entry.entry,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogbookNoteRow(
    note: Note,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The tag is keyed by the visible title, because the row's id is a generated
    // ULID that a UI-automation flow never learns — the title is what the user
    // sees, and therefore what a flow can address.
    val title = note.title.ifBlank { "(untitled)" }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp)
                .testTag(TestTags.logbookNoteByTitle(title)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectableText(
            text = title,
            style = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurface,
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
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(TaskSpacing.sm))
        val duration = entry.durationMs?.let { formatElapsed(it) } ?: "running"
        SelectableText(
            text = "${entry.kind.name.lowercase()} · $duration",
            style = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Formats a [LocalDate] as a human-readable day label.
 * "Today", "Yesterday", or "Jan 1" for other days.
 * @param today Derived from the injected clock (#91) — stable within a composition.
 * @param yesterday Derived from the injected clock (#91) — stable within a composition.
 */
private fun formatDayLabel(
    date: LocalDate,
    today: LocalDate,
    yesterday: LocalDate,
): String =
    when (date) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> "${monthAbbreviation(date.month)} ${date.day}"
    }
