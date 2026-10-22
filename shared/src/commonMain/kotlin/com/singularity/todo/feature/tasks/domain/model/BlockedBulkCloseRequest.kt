package com.singularity.todo.feature.tasks.domain.model

/**
 * A pending confirmation: part of a bulk close has unfinished prerequisites, and
 * the user has been asked whether to close the selection anyway.
 *
 * ## Why it is a separate request
 *
 * The single close and the bulk close ask the same question — "not while *these*
 * are open" — about a different subject: one task, or a selection. A request
 * that carried both would have a title and a list of names that mean different
 * things depending on which was asked, and the dialog would have to guess.
 *
 * ## Why it is state and not an event
 *
 * The dialog is open across a rotation, and the selection it applies to is
 * still there when the screen comes back. So the request is published in the
 * state, the screen renders the dialog from it, and the user's answer comes
 * back as an intent.
 *
 * @property taskIds the whole selection the close applies to.
 * @property blockedTaskIds the tasks in it that are in the way.
 * @property blockedTaskTitles the titles of those tasks.
 */
data class BlockedBulkCloseRequest(
    val taskIds: List<TaskId>,
    val blockedTaskIds: List<TaskId>,
    val blockedTaskTitles: List<String>,
)
