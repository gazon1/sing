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
 * @param onArchive      archive the task. In this codebase "archive" *is* the soft-delete
 *        path — `TaskLifecycleSlot.archive` calls `taskRepo.softDelete`, and the Archive
 *        screen lists soft-deleted tasks — so this performs the same write as
 *        [onDelete]. The menu shows both because the task list and the archive screen
 *        each describe that write in the user's vocabulary.
 * @param onShare        hand the task's text to the platform share sheet. A platform
 *        action, implemented by `core.files.SharePort`; no use case is the right shape.
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
    val onArchive: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onDismiss: () -> Unit = {},
) {
    companion object {
        /** All actions are no-ops — useful for previews and smoke tests. */
        internal val Empty = TaskMenuActions(onDismiss = {})
    }
}
