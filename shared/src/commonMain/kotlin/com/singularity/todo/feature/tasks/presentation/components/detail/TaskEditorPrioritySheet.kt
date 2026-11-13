package com.singularity.todo.feature.tasks.presentation.components.detail
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.theme.priorityIcon
import com.singularity.todo.feature.tasks.presentation.theme.priorityIconTint
import com.singularity.todo.feature.tasks.presentation.theme.priorityLabel

/**
 * Priority selection sheet: one row per level, the level's own glyph, and a radio
 * button that says which one is chosen.
 *
 * The sheet and the priority row share the glyphs and the tints, so the level looks
 * the same in both places. The raw priority hues are not used here: most of them
 * measure below 3:1 on a light surface, and a picker whose own options are hard to
 * read is a picker that cannot be used without guessing.
 */
@Composable
fun TaskEditorPrioritySheet(
    selected: TaskPriority,
    onSelect: (TaskPriority) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = TaskSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(TaskSpacing.sm),
    ) {
        TaskPriority.entries.forEach { priority ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(priority.testTag())
                        .clickable { onSelect(priority) }
                        .padding(
                            horizontal = TaskSpacing.cardPaddingHorizontal,
                            vertical = TaskSpacing.md,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = priorityIcon(priority),
                    contentDescription = null,
                    tint = priorityIconTint(priority),
                    modifier = Modifier.size(TaskSpacing.iconSize),
                )
                Text(
                    text = priorityLabel(priority),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = TaskSpacing.md),
                )
                RadioButton(
                    selected = priority == selected,
                    // The row above is the button: it carries the label, the tag and
                    // the action. A radio button *inside* it would be a second
                    // focusable with no name of its own — the screen reader would
                    // announce an unlabelled radio and then the labelled row.
                    onClick = null,
                )
            }
        }
    }
}

/**
 * The stable test tag for a priority option.
 *
 * Keyed on the enum, not on the priority label: the labels are user-facing
 * copy that the app translates, so a tag built from them would break in every
 * locale but the one it was written in. `TestTags.PRIORITY_OPTION_*` already
 * exists for exactly this, and this mapping is the only place the two meet.
 */
private fun TaskPriority.testTag(): String =
    when (this) {
        TaskPriority.None -> TestTags.PRIORITY_OPTION_NONE
        TaskPriority.Low -> TestTags.PRIORITY_OPTION_LOW
        TaskPriority.Medium -> TestTags.PRIORITY_OPTION_MEDIUM
        TaskPriority.High -> TestTags.PRIORITY_OPTION_HIGH
        TaskPriority.Urgent -> TestTags.PRIORITY_OPTION_URGENT
    }
