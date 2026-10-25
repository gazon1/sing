package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.domain.usecase.BlockedTasksException
import com.singularity.todo.feature.tasks.presentation.state.TaskCompletionIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.toImmutableList

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
 * A task with an unfinished prerequisite is not closed on the first request:
 * the write refuses, and the slot asks instead. Nothing is written until the
 * user confirms.
 *
 * The guard is the **write**, on both branches: an ordinary task is closed by
 * the guarded statement, and a recurring one by the roll-forward, which
 * refuses in the same way. A check that ran before the write would have a
 * window between the two, and the window is where another device reopens the
 * prerequisite.
 *
 * The check that names the prerequisites is the shared pure predicate, not a
 * rule of this slot: the agenda asks the same question the same way, and two
 * rules would be two answers.
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
        if (completing && task.recurrence != null) {
            // A recurring task is closed by rolling the recurrence forward,
            // and that write refuses while a prerequisite is open — the same
            // refusal the ordinary close makes. The question is asked when the
            // write says no, not before it: a check that runs before the write
            // has a window between the two, and the window is where another
            // device reopens the prerequisite.
            scope.launch {
                core.completeRecurring(task.id, force)
                    .onSuccess { onSaved("Recurrence completed") }
                    .onFailure { error ->
                        if (error is BlockedTasksException) {
                            val blockers = Blocking.activeBlockers(task, allTasks.value)
                            if (blockers.isNotEmpty()) {
                                askAboutBlockers(task, blockers)
                            } else {
                                onError("Save failed")
                            }
                        } else {
                            onError("Failed to complete recurring task")
                        }
                    }
            }
            return
        }
        scope.launch {
            if (completing) {
                // The write decides: it refuses while a prerequisite is open,
                // and the refusal is part of the statement rather than a check
                // that runs before it.
                val completed = core.taskRepo.complete(task.id, force).getOrElse {
                    onError("Save failed")
                    return@launch
                }
                if (!completed) {
                    val blockers = Blocking.activeBlockers(task, allTasks.value)
                    if (blockers.isEmpty()) {
                        onError("Save failed")
                        return@launch
                    }
                    askAboutBlockers(task, blockers)
                    return@launch
                }
                onSaved("Marked done")
            } else {
                // Taking a task back into work is never gated — and it brings
                // its project back with it when that project was closed.
                core.completion.reopen(task.id)
                    .onSuccess { onSaved("Marked active") }
                    .onFailure { onError("Save failed") }
            }
        }
    }

    private fun askAboutBlockers(task: Task, blockers: List<Task>) {
        onBlockedClose(
            BlockedCloseRequest(
                taskId = task.id,
                taskTitle = task.title,
                blockerTitles = blockers.map { it.title }.toImmutableList(),
            ),
        )
    }
}
