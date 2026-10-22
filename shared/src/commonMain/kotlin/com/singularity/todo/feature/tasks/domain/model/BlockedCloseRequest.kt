package com.singularity.todo.feature.tasks.domain.model

/**
 * A pending confirmation: this task has unfinished prerequisites, and the user
 * has been asked whether to close it anyway.
 *
 * ## Why it carries the names
 *
 * The answer to "may this task be closed?" is not a yes/no. It is "not while
 * *these* are open" — and a confirmation that does not say which prerequisites
 * are in the way is a question the user cannot answer.
 *
 * ## Why it is state and not an event
 *
 * The dialog is open across a rotation. A one-shot event is delivered once and
 * gone; the screen that comes back after the rotation would find nothing to
 * show, and the user would be left with a task that silently refused to close.
 * So the request is published in the state, the screen renders the dialog from
 * it, and the user's answer comes back as an intent.
 *
 * @property taskId the task that was asked about.
 * @property taskTitle the task's title, for the dialog's wording.
 * @property blockerTitles the titles of the prerequisites that are still open.
 */
data class BlockedCloseRequest(val taskId: TaskId, val taskTitle: String, val blockerTitles: List<String>)
