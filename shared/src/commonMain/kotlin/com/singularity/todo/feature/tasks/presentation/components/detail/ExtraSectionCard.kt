package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

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
 * The header draws one icon, and every section draws it the same way: the same
 * vector size ([TaskSpacing.iconSize]), the same tint and the same alignment.
 * The card takes the vector rather than a slot, so a section cannot draw its
 * header icon at a size of its own — that is how the checklist glyph ended up
 * at one size in the detail and another in the editor.
 */
@Composable
internal fun ExtraSectionCard(
    icon: ImageVector,
    label: String,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = TaskSpacing.cardPaddingHorizontal,
                    vertical = TaskSpacing.md,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .size(TaskSpacing.iconSize)
                            .testTag(TestTags.TASK_DETAIL_SECTION_ICON),
                )
                Spacer(modifier = Modifier.width(TaskSpacing.md))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(TaskSpacing.sm))
            content()
        }
    }
}
