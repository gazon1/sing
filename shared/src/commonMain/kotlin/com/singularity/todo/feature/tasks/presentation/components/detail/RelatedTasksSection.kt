package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.collections.immutable.ImmutableList

data class RelatedTaskItemUi(
    val id: TaskId,
    val title: String,
    val verb: DependencyVerb,
    val projectName: String?,
    val canOpen: Boolean = true,
)

/**
 * Compact related-tasks section for task detail.
 *
 * Shows current relations with enough context to disambiguate and an explicit
 * action to add one related task at a time.
 */
@Composable
fun RelatedTasksSection(
    items: ImmutableList<RelatedTaskItemUi>,
    onAdd: () -> Unit,
    onRemove: (TaskId) -> Unit,
    onOpen: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtraSectionCard(
        icon = { Icon(Icons.Filled.Link, contentDescription = null) },
        label = "Related tasks",
        modifier = modifier.testTag(TestTags.TASK_DETAIL_RELATED_TASKS_SECTION),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (items.isEmpty()) {
                    EmptySectionHint(
                        text = "No related tasks yet.",
                    )
                }
                items.forEach { item ->
                    RelatedTaskRow(
                        item = item,
                        onRemove = { onRemove(item.id) },
                        onOpen = { if (item.canOpen) onOpen(item.id) },
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .touchTarget()
                            .clickable(onClick = onAdd)
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                            .testTag(TestTags.TASK_DETAIL_ADD_RELATED_TASK),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Add related task",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}

@Composable
private fun RelatedTaskRow(
    item: RelatedTaskItemUi,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TestTags.relatedTaskItem(item.id.value))
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .then(if (item.canOpen) Modifier.clickable(onClick = onOpen) else Modifier)
                    .padding(end = 8.dp),
        ) {
            SelectableText(
                text = item.title.ifBlank { "(Untitled)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val relationLabel =
                when (item.verb) {
                    DependencyVerb.BLOCKS -> {
                        "Blocks this task"
                    }

                    DependencyVerb.FOLLOWS_UP -> {
                        "Follows up"
                    }

                    else -> {
                        item.verb.name
                            .lowercase()
                            .replaceFirstChar { it.uppercase() }
                    }
                }
            val subtitle =
                if (item.projectName.isNullOrBlank()) {
                    relationLabel
                } else {
                    "$relationLabel · ${item.projectName}"
                }
            Spacer(Modifier.height(2.dp))
            SelectableText(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier =
                Modifier
                    .touchTarget()
                    .size(36.dp)
                    .testTag(TestTags.relatedTaskRemove(item.id.value)),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove related task",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
