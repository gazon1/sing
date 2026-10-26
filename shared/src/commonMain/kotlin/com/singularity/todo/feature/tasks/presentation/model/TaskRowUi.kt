package com.singularity.todo.feature.tasks.presentation.model

import com.singularity.todo.core.ui.components.formatRussianDueDate
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.TaskRowItem
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.datetime.LocalDate

/**
 * The row as the list renders it.
 *
 * One builder for every screen that shows a task in a row — the agenda and
 * the plan timeline — so a task looks the same wherever the user meets it, and
 * a field added to the row does not have to be added twice.
 *
 * @param today The date the row's overdue state is judged against.
 * @param isSelected Whether the row is part of a multi-selection.
 */
fun TaskRowItem.toTaskUi(
    today: LocalDate,
    isSelected: Boolean = false,
): TaskUi = TaskUi(
    id = task.id,
    title = task.title,
    project = null,
    dueLabel = formatRussianDueDate(task.dueDate),
    parentId = task.parentTaskId?.value,
    indentLevel = if (task.parentTaskId != null) 1 else 0,
    isRecurring = false,
    isPinned = task.isPinned,
    priority = task.priority,
    isCompleted = task.isCompleted,
    isOverdue = TaskComputed.isOverdue(task, today),
    isSelected = isSelected,
    blockedBy = task.blockedBy.toImmutableSet(),
    isBlocked = isBlocked,
    seriesDates = seriesDates,
)
