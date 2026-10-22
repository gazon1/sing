package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.logic.activeBlockers
import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.presentation.state.TaskCompletionIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Marking the task complete.
 *
 * A recurring task is not completed in place: completing one rolls the recurrence forward
 * and creates the next occurrence, so the write goes through
 * [TaskCoreDeps.completeRecurring] rather than stamping `completedAt`. The state this slot
 * publishes is the projection the hero row renders, so the screen does not have to reach
 * into the task row for completion.
 *
 * ## The blocked-completion guard
 *
 * A task with an unfinished prerequisite is not closed on the first request: the slot asks
 * instead, and nothing is written until the user confirms. The guard sits **before** the
 * recurrence branch on purpose — rolling a recurrence forward is still closing the task,
 * and it has to ask too.
 *
 * The check is the shared pure predicate, not a rule of this slot: the agenda asks the
 * same question the same way, and two rules would be two answers.
 */
class TaskCompletionSlot(
    private val core: TaskCoreDeps,
    private val context: TaskContextDeps,
    private val scope: AutoCloseableCoroutineScope,
    private val taskFlow: StateFlow<Task?>,
    /**
     * Every task the user can see, so the slot can answer "which prerequisites of this
     * task are still open". A prerequisite that is finished, discarded, or gone is not in
     * the way — and the answer has to come from the same list the badge was computed
     * from, or the two would disagree.
     */
    private val allTasks: StateFlow<List<Task>>,
    private val onError: (String) -> Unit,
    private val onSaved: (String) -> Unit,
    /** Raised when a close is refused until the user confirms. */
    private val onBlockedClose: (BlockedCloseRequest) -> Unit,
) : FeatureSlot<TaskCompletionState, TaskCompletionIntent> {

    private val _state = MutableStateFlow(TaskCompletionState())
    override val state: StateFlow<TaskCompletionState> = _state.asStateFlow()

    init {
        scope.launch {
            taskFlow.map { task ->
                TaskCompletionState(
                    isCompleted = task?.isCompleted == true,
                    completedAt = task?.completedAt,
                    hasRecurrence = task?.recurrence != null,
                )
            }.collect { _state.value = it }
        }
    }

    override fun onIntent(intent: TaskCompletionIntent) {
        val task = taskFlow.value ?: return
        when (intent) {
            TaskDetailIntent.Domain.ToggleComplete -> toggleComplete(task)

            is TaskDetailIntent.Domain.ConfirmBlockedClose -> {
                // The confirmation names a task; this slot owns the task on this
                // screen and nothing else. A subtask belongs to the children slot.
                if (task.id == intent.taskId) toggleComplete(task, force = true)
            }
        }
    }

    private fun toggleComplete(task: Task, force: Boolean = false) {
        val completing = task.completedAt == null
        if (completing && !force) {
            val blockers = activeBlockers(task, allTasks.value)
            if (blockers.isNotEmpty()) {
                onBlockedClose(
                    BlockedCloseRequest(
                        taskId = task.id,
                        taskTitle = task.title,
                        blockerTitles = blockers.map { it.title },
                    ),
                )
                return
            }
        }
        if (completing && task.recurrence != null) {
            scope.launch {
                core.completeRecurring(task.id)
                    .onSuccess { onSaved("Recurrence completed") }
                    .onFailure { onError("Failed to complete recurring task") }
            }
            return
        }
        val completedAt = if (completing) context.clock.now() else null
        scope.launch {
            core.updateTask(task.id) { it.copy(completedAt = completedAt) }
                .onSuccess { onSaved(if (completedAt != null) "Marked done" else "Marked active") }
                .onFailure { onError("Save failed") }
        }
    }
}
