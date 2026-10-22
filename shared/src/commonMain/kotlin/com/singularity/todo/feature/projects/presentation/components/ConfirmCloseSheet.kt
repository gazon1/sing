package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.singularity.todo.core.ui.text.SelectableText

/**
 * Confirmation sheet for closing a project.
 *
 * Closing a project also closes the tasks that are still open in it, so the sheet
 * says how many — a cascade the user did not ask for one task at a time deserves to
 * be counted out loud.
 *
 * @param openTaskCount how many of the project's tasks are still open.
 * @param onConfirm called when the user confirms; nothing is written before it.
 * @param onDismiss called when the user backs out; nothing was written.
 */
@Composable
fun ConfirmCloseSheet(openTaskCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close project?") },
        text = { SelectableText(text = confirmCloseText(openTaskCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Close project") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Confirmation shown when a project cannot be closed yet because one of its tasks
 * has an unfinished prerequisite.
 *
 * Nothing was written when this appeared. The names are the point: the user is
 * being asked to close work whose prerequisites are still open, and they cannot
 * act on that without knowing which ones.
 *
 * @param blockedTaskTitles the titles of the tasks that are in the way.
 * @param openTaskCount how many of the project's tasks the close will close.
 * @param onConfirm called when the user confirms; the cascade proceeds.
 * @param onDismiss called when the user backs out; nothing was written.
 */
@Composable
fun ConfirmBlockedCloseDialog(
    blockedTaskTitles: List<String>,
    openTaskCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Some tasks are not finished") },
        text = { SelectableText(text = confirmBlockedCloseText(blockedTaskTitles, openTaskCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Close anyway") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The body of the close confirmation: how many tasks the close will close with it.
 *
 * A pure function so the wording can be asserted without a Compose runtime.
 *
 * @param openTaskCount how many of the project's tasks are still open.
 */
fun confirmCloseText(openTaskCount: Int): String = when (openTaskCount) {
    0 -> "This project has no open tasks. The project will be closed."
    1 -> "1 open task will be closed with it."
    else -> "$openTaskCount open tasks will be closed with it."
}

/**
 * The body of the blocked close confirmation: the tasks in the way, and how many
 * tasks the close will close.
 *
 * A pure function so the wording can be asserted without a Compose runtime.
 *
 * @param blockedTaskTitles the titles of the tasks that are in the way.
 * @param openTaskCount how many of the project's tasks the close will close.
 */
fun confirmBlockedCloseText(blockedTaskTitles: List<String>, openTaskCount: Int): String {
    val names = blockedTaskTitles.joinToString(separator = "\n") { "• $it" }
    val tasks = when (openTaskCount) {
        0 -> "No tasks will be closed."
        1 -> "1 task will be closed."
        else -> "$openTaskCount tasks will be closed."
    }
    return "These tasks have unfinished prerequisites:\n$names\n\n$tasks"
}
