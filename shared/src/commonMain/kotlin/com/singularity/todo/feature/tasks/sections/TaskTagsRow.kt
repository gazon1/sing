package com.singularity.todo.feature.tasks.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.components.TaskDetailActions

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsRow(
    tags: List<Tag>,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tags.forEach { tag ->
            AssistChip(
                onClick = { actions.onRemoveTag(tag.id) },
                label = { Text(tag.name, style = MaterialTheme.typography.labelMedium) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove ${tag.name}",
                        modifier = Modifier.size(14.dp),
                    )
                },
            )
        }
        SuggestionChip(
            onClick = actions::onAddTag,
            label = { Text("+ Add tag") },
            icon = {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            },
        )
    }
}
