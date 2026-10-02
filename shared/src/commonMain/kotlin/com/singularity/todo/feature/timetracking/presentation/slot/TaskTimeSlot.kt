package com.singularity.todo.feature.timetracking.presentation.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskTimeSlotIntent
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.feature.timetracking.domain.TimeTrackingRepository
import com.singularity.todo.feature.timetracking.domain.model.TaskTimeSlotState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * FeatureSlot that owns the time-tracking UI slice for a task detail screen.
 *
 * Provides three intents: [TaskTimeSlotIntent.Start], [TaskTimeSlotIntent.Stop],
 * and [TaskTimeSlotIntent.CreateManual]. The UI drives the running timer via [Tick].
 *
 * Single-open-entry invariant is enforced by [TimeTrackingRepository].
 *
 * @param taskId The task this slot tracks time for.
 * @param timeTrackingRepo Repository for time entries.
 * @param currentUser Provides the current user ID for repository calls.
 * @param scope Drives all coroutines; cancelled when the task detail screen leaves.
 * @param taskFlow Emits the current task; null while loading or after deletion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskTimeSlot(
    private val taskId: TaskId,
    private val timeTrackingRepo: TimeTrackingRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val scope: AutoCloseableCoroutineScope,
    taskFlow: StateFlow<Task?>,
) : FeatureSlot<TaskTimeSlotState, TaskTimeSlotIntent> {

    private val _state = MutableStateFlow<TaskTimeSlotState>(TaskTimeSlotState.Loading)
    override val state: StateFlow<TaskTimeSlotState> = _state.asStateFlow()

    init {
        scope.launch {
            taskFlow.filterNotNull()
                .flatMapLatest {
                    timeTrackingRepo.watchEntries(taskId)
                }
                .collect { entries ->
                    val openEntry = timeTrackingRepo.getOpenEntry(currentUser.scopedUserId.value)
                    val workEntries = entries.filter { it.kind == TimeEntryKind.Work }
                    val totalMs = workEntries.sumOf { it.durationMs ?: 0L }
                    _state.value = when {
                        openEntry != null -> TaskTimeSlotState.Running(
                            entry = openEntry,
                            elapsedMs = 0L,
                        )

                        workEntries.isEmpty() -> TaskTimeSlotState.Idle

                        else -> TaskTimeSlotState.Loaded(
                            entries = workEntries.sortedByDescending { it.startedAt },
                            totalWorkMs = totalMs,
                        )
                    }
                }
        }
    }

    override fun onIntent(intent: TaskTimeSlotIntent) {
        when (intent) {
            is TaskTimeSlotIntent.Start -> start()

            is TaskTimeSlotIntent.Stop -> stop()

            is TaskTimeSlotIntent.CreateManual -> createManual(intent)

            is TaskTimeSlotIntent.Tick -> tick(intent.elapsedMs)

            // Unknown variant —silently ignore to survive future intent additions
            else -> { /* no-op */ }
        }
    }

    private fun start() {
        val current = _state.value
        if (current is TaskTimeSlotState.Running) return
        scope.launch {
            timeTrackingRepo.startEntry(
                taskId = taskId,
                userId = currentUser.scopedUserId.value,
                kind = TimeEntryKind.Work,
                source = TimeEntrySource.Timer,
            ).onFailure { /* UI updates from the flow */ }
        }
    }

    private fun stop() {
        scope.launch {
            timeTrackingRepo.stopEntry(currentUser.scopedUserId.value)
                .onFailure { /* UI updates from the flow */ }
        }
    }

    private fun createManual(intent: TaskTimeSlotIntent.CreateManual) {
        scope.launch {
            timeTrackingRepo.createManualEntry(
                taskId = taskId,
                userId = currentUser.scopedUserId.value,
                startedAt = intent.startedAtMs,
                endedAt = intent.endedAtMs,
                kind = intent.kind,
                note = intent.note,
            ).onFailure { /* UI updates from the flow */ }
        }
    }

    private fun tick(elapsedMs: Long) {
        val current = _state.value
        if (current is TaskTimeSlotState.Running) {
            _state.value = current.copy(elapsedMs = elapsedMs)
        }
    }
}
