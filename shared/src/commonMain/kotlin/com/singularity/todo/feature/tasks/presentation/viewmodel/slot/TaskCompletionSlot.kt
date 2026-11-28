package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.tasks.domain.model.BlockedCloseRequest
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskContextDeps
import com.singularity.todo.feature.tasks.domain.model.TaskCoreDeps
import com.singularity.todo.feature.tasks.domain.state.TaskCompletionIntent
import com.singularity.todo.feature.tasks.domain.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.domain.usecase.BlockedTasksException
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

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
    private val scope: CoroutineScope,
    private val taskFlow: StateFlow<Task?>,
    private val onError: (String) -> Unit,
    private val onSaved: (String) -> Unit,
    /** Raised when a close is refused until the user confirms. */
    private val onBlockedClose: (BlockedCloseRequest) -> Unit,
) : FeatureSlot<TaskCompletionState, TaskCompletionIntent> {
    private val _state = MutableStateFlow(TaskCompletionState())
    override val state: StateFlow<TaskCompletionState> = _state.asStateFlow()

    init {
        scope.launch {
            taskFlow
                .map { task ->
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
            TaskDetailIntent.Domain.ToggleComplete -> {
                toggleComplete(task)
            }

            is TaskDetailIntent.Domain.ConfirmBlockedClose -> {
                // The confirmation names a task; this slot owns the task on this
                // screen and nothing else. A subtask belongs to the children slot.
                if (task.id == intent.taskId) toggleComplete(task, force = true)
            }
        }
    }

    private fun toggleComplete(
        task: Task,
        force: Boolean = false,
    ) {
        val completing = task.completedAt == null
        if (completing && task.recurrence != null) {
            // A recurring task is closed by rolling the recurrence forward,
            // and that write refuses while a prerequisite is open — the same
            // refusal the ordinary close makes. The question is asked when the
            // write says no, not before it: a check that runs before the write
            // has a window between the two, and the window is where another
            // device reopens the prerequisite.
            scope.launch {
                val result = core.completeRecurring(task.id, force)
                val error = result.exceptionOrNull()
                when {
                    result.isSuccess -> onSaved("Recurrence completed")
                    error is BlockedTasksException -> askAboutBlockers(task)
                    else -> onError("Failed to complete recurring task")
                }
            }
            return
        }
        scope.launch {
            if (completing) {
                // The write decides: it refuses while a prerequisite is open,
                // and the refusal is part of the statement rather than a check
                // that runs before it.
                val completed =
                    core.taskRepo.complete(task.id, force).getOrElse {
                        onError("Save failed")
                        return@launch
                    }
                if (!completed) {
                    askAboutBlockers(task)
                    return@launch
                }
                onSaved("Marked done")
            } else {
                // Taking a task back into work is never gated — and it brings
                // its project back with it when that project was closed.
                core.completion
                    .reopen(task.id)
                    .onSuccess { onSaved("Marked active") }
                    .onFailure { onError("Save failed") }
            }
        }
    }

    /**
     * Asks whether the task may be closed anyway, naming what is in the way.
     *
     * The prerequisites are read for this one task. The slot used to be handed every
     * task of the profile and picked the blockers out of that list, which made opening
     * a task detail cost the whole profile — on a 50k dataset the heap was spent before
     * the screen rendered.
     *
     * A write that refused and a read that finds nothing in the way disagree only when
     * another device reopened the prerequisite in between; the caller reports that as a
     * plain save failure rather than a confirmation about no one.
     *
     * The read is the one thing here that can fail on its own — it is the first database
     * call this path makes that is not the guarded write. A failure reports through the
     * same save message instead of escaping to the scope, where the user would see the
     * close silently do nothing.
     */
    private suspend fun askAboutBlockers(task: Task) {
        val blockers =
            runCatchingCancellable { core.taskRepo.openBlockersOf(task.id) }.getOrElse {
                onError("Save failed")
                return
            }
        if (blockers.isEmpty()) {
            // The write refused but nothing is open any more: another device
            // reopened and re-closed the prerequisite in between. A confirmation
            // about no one is worse than the plain failure.
            onError("Save failed")
            return
        }
        onBlockedClose(
            BlockedCloseRequest(
                taskId = task.id,
                taskTitle = task.title,
                blockerTitles = blockers.map { it.title }.toImmutableList(),
            ),
        )
    }
}
