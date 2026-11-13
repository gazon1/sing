package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.SectionHeader

/**
 * The one line a section shows when it has nothing to show.
 *
 * A section that is empty and says so reads as deliberate; a section that is empty
 * and says nothing reads as a leftover. The line states the emptiness and points
 * at the way in — the add-row the section draws under it.
 */
@Composable
internal fun EmptySectionHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Reusable card wrapper for task detail extra sections (tags, checklist, attachments, etc.).
 *
 * @param onToggle Called when the header row is tapped. `null` means the section
 *        cannot be collapsed: the header is a label and the content is always
 *        drawn. With a toggle, the content is drawn only while the section is
 *        expanded — collapsing hides it and changes no data.
 * @param trailing Content drawn at the end of the header row.
 * @param headerTestTag The tag of the header row. The header is the section's
 *        only target, so the tag belongs to it — a flow that wants to open the
 *        section taps the row, not the card around it.
 */
@Composable
internal fun ExtraSectionCard(
    icon: @Composable () -> Unit,
    label: String,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    headerTestTag: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SectionHeader(
                icon = icon,
                label = label,
                expanded = expanded,
                onToggle = onToggle,
                trailing = trailing,
                testTag = headerTestTag,
            )
            if (onToggle == null || expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
        }
    }
}
