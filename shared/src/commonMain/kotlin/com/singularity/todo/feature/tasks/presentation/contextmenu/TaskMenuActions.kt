package com.singularity.todo.feature.tasks.presentation.contextmenu

import androidx.compose.runtime.Stable
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Callbacks for a task's context menu. Each action is nullable — a `null` value
 * means the corresponding menu item is hidden (not shown at all).
 *
 * This is a plain data class (not a sealed class or `@JvmInline value class`)
 * so that callers can construct it with named arguments without boilerplate.
 *
 * @param onTogglePin     pin / unpin the task
 * @param onToggleComplete mark the task complete / reopen it
 * @param onDelete        delete the task (no undo in v1)
 * @param onToggleExpand  expand / collapse a parent task's children
 * @param onSetDependencies open the dependency picker to set depends-on tasks
 * @param onAiAction     dispatch an AI action; the lambda receives the specific action
 * @param onDismiss       close the context menu (called by every item's onClick)
 */
@Stable
data class TaskMenuActions(
    val onTogglePin: (() -> Unit)? = null,
    val onToggleComplete: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onToggleExpand: (() -> Unit)? = null,
    val onSetDependencies: ((Set<TaskId>) -> Unit)? = null,
    val onAiAction: ((TaskAiAction) -> Unit)? = null,
    val onDismiss: () -> Unit = {},
) {
    companion object {
        /** All actions are no-ops — useful for previews and smoke tests. */
        val Empty = TaskMenuActions(onDismiss = {})
    }
}
