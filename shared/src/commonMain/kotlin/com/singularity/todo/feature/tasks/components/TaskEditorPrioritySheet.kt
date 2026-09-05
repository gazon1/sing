package com.singularity.todo.feature.tasks.components

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
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.TaskPriority

/**
 * Priority selection sheet with colored flag icons and radio buttons.
 */
@Composable
fun TaskEditorPrioritySheet(
    selected: TaskPriority,
    onSelect: (TaskPriority) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TaskPriority.entries.forEach { priority ->
            val meta = priorityMeta(priority)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
