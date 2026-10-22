package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable

/**
 * The confirmation shown when the user tries to close a task whose prerequisites
 * are still open.
 *
 * The task is not closed and nothing is written when this appears: the write
 * happens only if the user confirms.
 *
 * ## Why a shared component
 *
 * The agenda and the task screen both close tasks, and both have to ask the
 * same question the same way. Two copies of the same words would be two
 * chances for them to drift, and the user would meet two different
 * confirmations for one rule.
 *
 * The component takes the task's title and the prerequisite titles as plain
 * strings and knows nothing about tasks — it lives in `core/ui`, which does not
 * depend on any feature.
 *
 * @param taskTitle the title of the task that was asked about.
 * @param blockerTitles the titles of the prerequisites that are still open.
 * @param onConfirm called when the user chooses to close the task anyway.
 * @param onDismiss called when the user backs out; nothing was written.
 */
@Composable
fun BlockedCompletionDialog(
    taskTitle: String,
    blockerTitles: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = ConfirmActionDialog(
    title = BLOCKED_COMPLETION_TITLE,
    text = blockedCompletionText(taskTitle, blockerTitles),
    confirmButtonText = BLOCKED_COMPLETION_CONFIRM,
    dismissButtonText = BLOCKED_COMPLETION_DISMISS,
    onConfirm = onConfirm,
    onDismiss = onDismiss,
)

/** The heading of the blocked-completion dialog. */
const val BLOCKED_COMPLETION_TITLE = "Task is blocked"

/** The label of the button that closes the task anyway. */
const val BLOCKED_COMPLETION_CONFIRM = "Close anyway"

/** The label of the button that leaves the task open. */
const val BLOCKED_COMPLETION_DISMISS = "Cancel"

/**
 * The body of the blocked-completion dialog: the prerequisites that are still
 * open.
 *
 * A pure function so the wording can be asserted without a Compose runtime —
 * the list of prerequisites is the whole point of the dialog, and a dialog
 * that does not name them is a dialog that cannot be acted on.
 *
 * @param taskTitle the title of the task that was asked about.
 * @param blockerTitles the titles of the prerequisites that are still open.
 */
fun blockedCompletionText(taskTitle: String, blockerTitles: List<String>): String {
    val subject = if (taskTitle.isBlank()) "This task" else "“$taskTitle”"
    if (blockerTitles.isEmpty()) return "$subject has unfinished prerequisites."
    return "$subject has unfinished prerequisites:\n" +
        blockerTitles.joinToString(separator = "\n") { "• $it" }
}
