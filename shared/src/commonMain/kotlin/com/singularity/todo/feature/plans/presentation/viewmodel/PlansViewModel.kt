package com.singularity.todo.feature.plans.presentation.viewmodel

import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.plans.domain.logic.TimelineGrouper
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * The plan screen's ViewModel.
 *
 * It reads two things and combines them: the granularity the profile chose,
 * and the tasks. The sections are a pure function of the two, so the ViewModel
 * holds no logic of its own beyond collecting them.
 *
 * The granularity is read from the settings rather than kept in memory, which
 * is what makes the choice survive leaving the screen.
 */
class PlansViewModel(
    private val deps: PlansDeps,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<PlansUiState, PlansIntent, PlansUiEvent>(
        initialState = PlansUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /**
     * Today, in the user's own time zone.
     *
     * The same date the date picker marks and the same date the sections are
     * built against, so the two cannot disagree about which day it is.
     */
    val today: LocalDate = todayAt(deps.clock, deps.timeZone.current())

    /**
     * The tasks as they were last read.
     *
     * Kept so that an intent can tell a completed task from an open one
     * without asking the repository again — and so that a task that is not in
     * the plan at all is not acted upon by a stale row.
     */
    private val latestTasks = MutableStateFlow<List<Task>>(emptyList())

    init {
        vmScope.launch {
            combine(
                deps.settings.granularity,
                deps.taskRepo.observeByFilter(TaskFilter.All),
            ) { granularity, tasks -> granularity to tasks }
                .map { (granularity, tasks) ->
                    latestTasks.value = tasks
                    PlansUiState.Loaded(
                        sections = TimelineGrouper.group(tasks, granularity, today).toImmutableList(),
                        granularity = granularity,
                        today = today,
                    )
                }
                .catch { throwable ->
                    crashReporter.report(throwable, LOAD_FAILED)
                    setState(PlansUiState.Error(throwable.message ?: "Could not load the plan"))
                }
                .collect { loaded -> setState(loaded) }
        }
    }

    override fun onIntent(intent: PlansIntent) {
        when (intent) {
            is PlansIntent.SetGranularity -> vmScope.launch {
                deps.settings.setGranularity(intent.granularity)
            }

            is PlansIntent.ToggleCompleted -> vmScope.launch {
                toggleCompleted(intent.taskId)
            }

            is PlansIntent.TaskSwipedAway -> vmScope.launch {
                swipeAway(intent.taskId)
            }
        }
    }

    /**
     * Completes a task, or takes it back into work.
     *
     * A completion that is refused — a prerequisite is still open — becomes an
     * error event rather than a silent no-op: the row would otherwise stay as
     * it is and the user would think the tap missed.
     */
    private suspend fun toggleCompleted(taskId: TaskId) {
        val task = latestTasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.completedAt != null) {
            deps.taskRepo.reopen(taskId).onFailure { error ->
                crashReporter.report(error, REOPEN_FAILED)
                emit(PlansUiEvent.ShowError("Could not reopen the task"))
            }
            return
        }

        val completed = deps.taskRepo.complete(taskId).getOrElse { error ->
            crashReporter.report(error, COMPLETE_FAILED)
            emit(PlansUiEvent.ShowError("Could not update the task"))
            return
        }
        if (!completed) {
            emit(PlansUiEvent.ShowError("The task has an unfinished prerequisite"))
        }
    }

    /**
     * Takes a task off the plan.
     *
     * The reminder is cancelled before the task goes: a deleted task that
     * still rings is worse than a deleted task that does not.
     */
    private suspend fun swipeAway(taskId: TaskId) {
        deps.reminderScheduler.cancelByTask(taskId, deps.currentUser.scopedUserId.value)
        deps.taskRepo.softDelete(taskId).onFailure { error ->
            crashReporter.report(error, DELETE_FAILED)
            emit(PlansUiEvent.ShowError("Could not delete the task"))
        }
    }

    private companion object {
        const val LOAD_FAILED = "plans.load_failed"
        const val COMPLETE_FAILED = "plans.complete_failed"
        const val REOPEN_FAILED = "plans.reopen_failed"
        const val DELETE_FAILED = "plans.delete_failed"
    }
}
