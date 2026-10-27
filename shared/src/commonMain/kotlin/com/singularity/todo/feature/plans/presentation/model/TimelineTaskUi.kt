package com.singularity.todo.feature.plans.presentation.model

import com.singularity.todo.core.ui.components.formatRussianDueDate
import com.singularity.todo.feature.plans.domain.model.TimelineTask
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import kotlinx.collections.immutable.toImmutableSet

/**
 * The timeline's row, drawn the way every other task list draws it.
 *
 * The row is the shared one — the same [TaskUi] the agenda builds — so a task
 * looks the same wherever the user meets it. The markings come with the
 * timeline's row, computed by the one shared policy, so they are the same
 * markings the agenda shows.
 */
fun TimelineTask.toTaskUi(): TaskUi = TaskUi(
    id = task.id,
    title = task.title,
    project = null,
    dueLabel = formatRussianDueDate(task.dueDate),
    parentId = task.parentTaskId?.value,
    indentLevel = if (task.parentTaskId != null) 1 else 0,
    isPinned = task.isPinned,
    priority = task.priority,
    isCompleted = task.completedAt != null,
    blockedBy = task.blockedBy.toImmutableSet(),
    markings = markings,
    seriesDates = seriesDates,
)
