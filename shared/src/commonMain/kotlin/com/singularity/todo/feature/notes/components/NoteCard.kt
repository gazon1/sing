package com.singularity.todo.feature.notes.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.extractPreviewText

/**
 * Internal card content — title, pin icon, preview, word count.
 * Both [NoteCard] and [SwipeableNoteCard][com.singularity.todo.feature.notes.SwipeableNoteCard]
 * use this to avoid duplicating the rendering logic.
 *
 * Does NOT include the [Card] wrapper — callers provide their own
 * Card with the appropriate color scheme and click/swipe modifiers.
 */
@Composable
internal fun NoteCardContent(
    note: Note,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        note.isFolder -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        note.color != null -> Color(note.color.value).copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.noteItem(note.id.value)),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Body: title + pin icon + preview
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = note.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (note.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(start = 4.dp),
                        )
                    }
                }
                val previewText = extractPreviewText(note.bodyMarkdown)
                if (previewText.isNotBlank()) {
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Trailing: word count
            Column(horizontalAlignment = Alignment.End) {
                if (note.wordCount > 0) {
                    Text(
                        text = "${note.wordCount} words",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Card representation of a single note. Stateless — every piece of behavior is
 * supplied via [onClick] (whole-row tap) and [actions] (per-button callbacks).
 *
 * Supports slot customization via [body] and [trailing] parameters following
 * Material 3 naming convention (`body` for main content, `trailing` for meta/actions).
 *
 * @param body    Main content slot — rendered inside the [Card].
 * @param trailing Meta slot — rendered inside the [Card], after [body].
 */
@Composable
fun NoteCard(
    note: Note,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    actions: NoteCardActions = NoteCardActions.Empty,
    modifier: Modifier = Modifier,
    body: @Composable RowScope.() -> Unit = { DefaultNoteCardBody(note, isSelected) },
    trailing: @Composable RowScope.() -> Unit = { DefaultNoteCardTrailing(note) },
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.noteItem(note.id.value))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                note.isFolder -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                note.color != null -> Color(note.color.value).copy(alpha = 0.15f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            body()
            trailing()
        }
    }
}

/**
 * Default body content for [NoteCard] — title with optional pin indicator and preview text.
 */
@Composable
internal fun RowScope.DefaultNoteCardBody(note: Note, isSelected: Boolean) {
    Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = note.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (note.isPinned) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(start = 4.dp),
                )
            }
        }
        val previewText = extractPreviewText(note.bodyMarkdown)
        if (previewText.isNotBlank()) {
            Text(
                text = previewText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Default trailing content for [NoteCard] — word count.
 */
@Composable
internal fun RowScope.DefaultNoteCardTrailing(note: Note) {
    Column(horizontalAlignment = Alignment.End) {
        if (note.wordCount > 0) {
            Text(
                text = "${note.wordCount} words",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
