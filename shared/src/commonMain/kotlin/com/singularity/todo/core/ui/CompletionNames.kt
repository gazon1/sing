package com.singularity.todo.core.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.checklist_item_checkbox_label
import singularity_todo.shared.generated.resources.subtask_checkbox_label
import singularity_todo.shared.generated.resources.task_checkbox_label
import singularity_todo.shared.generated.resources.untitled_note

/**
 * What a completion control completes.
 *
 * The kinds are named apart because a screen reader reads the name out loud:
 * "Task: Buy milk" and "Subtask: Buy milk" are different statements, and a
 * user who cannot see the row needs to hear which one is on it.
 */
enum class CompletionKind {
    /** A task of its own. */
    Task,

    /** A task that is a part of another task. */
    Subtask,

    /** An item of a checklist — a part that is not a task. */
    ChecklistItem,
}

/**
 * The accessible name of a control that completes something.
 *
 * The name is the kind and the title — "Subtask: Buy milk" — because the title
 * alone does not say what pressing the control will do, and the kind alone
 * does not say which one it is.
 *
 * The completed state is not in the name: it is a
 * [androidx.compose.ui.semantics.stateDescription], so the name of a control
 * stays the same when the control changes what it completed.
 *
 * A blank title falls back to [untitled_note]: a name that is only "Task" says
 * less than one that says "Task: Untitled".
 */
@Composable
fun completionName(
    kind: CompletionKind,
    title: String,
): String {
    val label: StringResource =
        when (kind) {
            CompletionKind.Task -> Res.string.task_checkbox_label
            CompletionKind.Subtask -> Res.string.subtask_checkbox_label
            CompletionKind.ChecklistItem -> Res.string.checklist_item_checkbox_label
        }
    val untitled = stringResource(Res.string.untitled_note)
    return stringResource(label, title.ifBlank { untitled })
}
