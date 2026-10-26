package com.singularity.todo.feature.tasks.domain.model

/**
 * The badge shown on a task row.
 *
 * One vocabulary for every screen that renders a task: the agenda and the
 * plans timeline both ask [com.singularity.todo.feature.tasks.domain.logic.computeTaskBadge]
 * what to show, so a task looks the same wherever the user meets it.
 *
 * The six cases and their meanings are unchanged by the move out of the agenda
 * feature — only the owner changed.
 */
enum class TaskBadge {
    Overdue,
    Recurring,
    Pinned,
    Completed,
    NoDate,
    Blocked,
}
