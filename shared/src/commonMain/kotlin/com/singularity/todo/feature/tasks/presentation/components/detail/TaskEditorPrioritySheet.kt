package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.priorityMeta

/**
 * Priority selection sheet with colored flag icons and radio buttons.
 */
@Composable
fun TaskEditorPrioritySheet(selected: TaskPriority, onSelect: (TaskPriority) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TaskPriority.entries.forEach { priority ->
            val meta = priorityMeta(priority)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(priority.testTag())
                    .clickable { onSelect(priority) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Flag,
                    contentDescription = null,
                    tint = meta.color,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = meta.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                RadioButton(
                    selected = priority == selected,
                    onClick = { onSelect(priority) },
                )
            }
        }
    }
}

/**
 * The stable test tag for a priority option.
 *
 * Keyed on the enum, not on [priorityMeta]'s label: the labels are user-facing
 * copy that the app translates, so a tag built from them would break in every
 * locale but the one it was written in. `TestTags.PRIORITY_OPTION_*` already
 * exists for exactly this, and this mapping is the only place the two meet.
 */
private fun TaskPriority.testTag(): String = when (this) {
    TaskPriority.None -> TestTags.PRIORITY_OPTION_NONE
    TaskPriority.Low -> TestTags.PRIORITY_OPTION_LOW
    TaskPriority.Medium -> TestTags.PRIORITY_OPTION_MEDIUM
    TaskPriority.High -> TestTags.PRIORITY_OPTION_HIGH
    TaskPriority.Urgent -> TestTags.PRIORITY_OPTION_URGENT
}
