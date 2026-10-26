package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState

/**
 * Workflow-state selection sheet.
 *
 * @param selected The state the task currently holds.
 * @param onSelect Called with the chosen state. The sheet does not write on its own:
 *   the caller owns the intent, so a completed task's frozen state stays frozen in
 *   one place.
 * @param enabled When false the options are shown but inert. A completed task keeps
 *   the state it had while it was worked on, and the shield is here as well as on the
 *   row because a sheet can stay open across a completion.
 */
@Composable
fun TaskEditorStateSheet(
    selected: TaskWorkflowState,
    onSelect: (TaskWorkflowState) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TaskWorkflowState.entries.forEach { state ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(state.testTag())
                    .then(if (enabled) Modifier.clickable { onSelect(state) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.label(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                RadioButton(
                    selected = state == selected,
                    onClick = null,
                )
            }
        }
    }
}

/**
 * The user-facing name of a workflow state.
 *
 * Keyed on the enum, not on the wire name or the stored value: those are stable and
 * this is copy.
 */
internal fun TaskWorkflowState.label(): String = when (this) {
    TaskWorkflowState.Open -> "No state"
    TaskWorkflowState.NextUp -> "Next up"
    TaskWorkflowState.InProgress -> "In progress"
}

/**
 * The stable test tag for a state option.
 *
 * Keyed on the enum, not on [label]: the labels are user-facing copy.
 */
internal fun TaskWorkflowState.testTag(): String = when (this) {
    TaskWorkflowState.Open -> TestTags.WORKFLOW_STATE_OPTION_OPEN
    TaskWorkflowState.NextUp -> TestTags.WORKFLOW_STATE_OPTION_NEXT_UP
    TaskWorkflowState.InProgress -> TestTags.WORKFLOW_STATE_OPTION_IN_PROGRESS
}
