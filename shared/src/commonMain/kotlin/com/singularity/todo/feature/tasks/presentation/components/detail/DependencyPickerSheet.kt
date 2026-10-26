package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap

/**
 * The verbs the picker can write.
 *
 * The enum carries more (`DUPLICATES`, `FIXES`, `SUPERSEDES`) because the table and
 * the sync wire carry them, but a person choosing a relation on this screen is
 * saying one of two things: "I cannot start until this is done" or "this picks up
 * where that left off". Offering the other three here would be offering words the
 * app's own views never read.
 */
private val PICKER_VERBS = listOf(DependencyVerb.BLOCKS, DependencyVerb.FOLLOWS_UP)

/**
 * Multi-select task dependency picker sheet.
 *
 * Allows the user to pick which tasks this task relates to, and what the relation
 * means: blocking (`BLOCKS`) or ordering (`FOLLOWS_UP`). Opens from the Dependencies
 * card in the task editor.
 *
 * @param currentDeps The current outgoing relations, keyed by the other task and
 *   carrying the verb. A selected task that is merely ordered rather than waited on
 *   must arrive with `FOLLOWS_UP`, or the picker would show it as blocking.
 * @param availableTasks All tasks available for selection (not trashed, not self).
 * @param onApply Called with the new relation map when the user confirms.
 * @param onDismiss Called when the user dismisses or cancels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DependencyPickerSheet(
    currentDeps: ImmutableMap<TaskId, DependencyVerb>,
    availableTasks: ImmutableList<Task>,
    onApply: (ImmutableMap<TaskId, DependencyVerb>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(currentDeps) { mutableStateOf(currentDeps) }

    TaskEditorSheetHost(
        title = "Dependencies",
        onClose = onDismiss,
        onConfirm = { onApply(selected) },
    ) {
        if (availableTasks.isEmpty()) {
            SelectableText(
                text = "No tasks available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(TestTags.DEPENDENCY_PICKER_EMPTY)
                    .padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(
                    items = availableTasks,
                    key = { it.id.value },
                ) { task ->
                    DependencyItem(
                        task = task,
                        verb = selected[task.id],
                        onToggle = { id ->
                            selected = if (id in selected) {
                                selected - id
                            } else {
                                selected + (id to DependencyVerb.BLOCKS)
                            }.toImmutableMap()
                        },
                        onVerbChange = { id, verb ->
                            if (id in selected) selected = (selected + (id to verb)).toImmutableMap()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DependencyItem(
    task: Task,
    verb: DependencyVerb?,
    onToggle: (TaskId) -> Unit,
    onVerbChange: (TaskId, DependencyVerb) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.dependencyItem(task.id.value))
            .clickable { onToggle(task.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = verb != null,
            onCheckedChange = { onToggle(task.id) },
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title.ifBlank { "(Untitled)" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.dueDate != null) {
                Text(
                    text = "Due: ${task.dueDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (verb != null) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PICKER_VERBS.forEach { candidate ->
                        FilterChip(
                            selected = verb == candidate,
                            onClick = { onVerbChange(task.id, candidate) },
                            label = { Text(candidate.label()) },
                            modifier = Modifier.testTag(
                                TestTags.dependencyVerb(task.id.value, candidate.name),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The user-facing name of a picker verb.
 *
 * Keyed on the enum, never on its `name`: the wire name is stable and the label is
 * copy the app may translate.
 */
private fun DependencyVerb.label(): String = when (this) {
    DependencyVerb.BLOCKS -> "Blocks"

    DependencyVerb.FOLLOWS_UP -> "Follows up"

    DependencyVerb.DUPLICATES,
    DependencyVerb.FIXES,
    DependencyVerb.SUPERSEDES,
    -> name.lowercase().replaceFirstChar { it.uppercase() }
}
