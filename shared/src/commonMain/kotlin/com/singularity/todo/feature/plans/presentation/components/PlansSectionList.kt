package com.singularity.todo.feature.plans.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.plans.domain.model.TimelineSection
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.plans.domain.model.TimelineTask
import com.singularity.todo.feature.tasks.presentation.components.list.SwipeableTaskRow
import com.singularity.todo.feature.plans.presentation.model.toTaskUi
import com.singularity.todo.feature.tasks.presentation.components.list.TaskRowFlat
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.LocalDate

/**
 * The timeline: one section per period, each holding the tasks due inside it.
 *
 * ## The row key
 *
 * The key is the section and the task together: `"{section.id}/{task.id}"`. The
 * periods are disjoint, so a task is in one section and the key never repeats —
 * but the key is written the careful way anyway, because a repeated key takes a
 * `LazyColumn` down with "Key … was already used" rather than showing the row
 * twice. A crash is a much worse failure than a duplicate row.
 *
 * ## The add control
 *
 * A dated section carries a "+" that creates a task due on that section's date.
 * The overdue section has no date to prefill, so it carries none: a task that
 * is late the moment it is created is not what the button would promise.
 *
 * @param sections The sections to render, oldest period first.
 * @param onTaskClick Called with the id of the task the user tapped.
 * @param onToggleCompleted Called when the user ticks a task off — or back on.
 * @param onSwipeAway Called when the user swipes a task away.
 * @param onAddInSection Called with the date a new task should be due.
 */
@Composable
fun PlansSectionList(
    sections: ImmutableList<TimelineSection>,
    onTaskClick: (TaskId) -> Unit,
    onToggleCompleted: (TaskId) -> Unit,
    onSwipeAway: (TaskId) -> Unit,
    onAddInSection: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (section in sections) {
            item(key = "header-${section.id}") {
                PlansSectionHeader(
                    section = section,
                    onAddClick = section.prefillDate?.let { date ->
                        { onAddInSection(date) }
                    },
                )
            }

            items(
                items = section.tasks,
                // Section and task together — see the KDoc above.
                key = { "${section.id}/${it.task.id.value}" },
            ) { rowItem ->
                PlansTaskRow(
                    rowItem = rowItem,
                    onTaskClick = onTaskClick,
                    onToggleCompleted = onToggleCompleted,
                    onSwipeAway = onSwipeAway,
                )
            }
        }
    }
}

/**
 * The header of a section: what the period is called, how much is in it, and
 * the control that adds a task to it.
 */
@Composable
private fun PlansSectionHeader(
    section: TimelineSection,
    onAddClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(TestTags.plansSection(section.id)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${section.label}  ·  ${section.tasks.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (onAddClick != null) {
                IconButton(
                    onClick = onAddClick,
                    modifier = Modifier.testTag(TestTags.plansSectionAdd(section.id)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create a task due ${section.label}",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * One task, drawn the way every other task list draws it.
 *
 * The row is the shared one — the same composable the agenda uses — so a task
 * looks the same wherever the user meets it.
 */
@Composable
private fun PlansTaskRow(
    rowItem: TimelineTask,
    onTaskClick: (TaskId) -> Unit,
    onToggleCompleted: (TaskId) -> Unit,
    onSwipeAway: (TaskId) -> Unit,
) {
    val taskId = rowItem.task.id
    val taskUi = remember(rowItem) { rowItem.toTaskUi() }

    SwipeableTaskRow(
        onDelete = { onSwipeAway(taskId) },
        content = {
            TaskRowFlat(
                task = taskUi,
                onToggleCompleted = { onToggleCompleted(taskId) },
                onClick = { onTaskClick(taskId) },
                showDivider = true,
            )
        },
    )
}
