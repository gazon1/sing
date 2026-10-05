package com.singularity.todo.feature.timetracking.presentation.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskTimeSlotIntent
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import com.singularity.todo.feature.timetracking.domain.model.TaskTimeSlotState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
                    // An error from a *write* is about that write, and the next
                    // emission is not news: the entry list is unchanged because
                    // the write never happened. Without this guard the refusal is
                    // overwritten by `Idle` a frame later and the section goes
                    // back to offering Start — which is the same silent failure
                    // the Error state was added to end, just with an extra frame.
                    //
                    // Caught by `a_refused_start_becomes_an_error_state`, which
                    // failed on the first run of the fix for exactly this reason.
                    if (_state.value is TaskTimeSlotState.Error) return@collect
                    // Re-derived per emission, not read from the cache: `scopedUserId`
                    // is a StateFlow seeded at construction and corrected by an async
                    // collector, so sampling it here would keep querying under the
                    // previous profile after a switch. `liveScopedUserId.first()` is a
                    // cold derivation — it answers with the truth now, and re-answers on
                    // the next entry. See ADR 2026-10-04-derived-identity-flows.md.
                    val userId = currentUser.liveScopedUserId.first()
                    val openEntry = timeTrackingRepo.getOpenEntry(userId)
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

            // Not `else -> { /* no-op */ }`, which is what it used to be. That
            // silence hid a real bug: `TaskDetailIntent.Domain.Start`
            // implemented the `TaskTimeSlotIntent` *marker* without being
            // `TaskTimeSlotIntent.Start`, so the coordinator handed the slot an
            // object this `when` did not match, and the timer's Start button did
            // nothing at all — no error, no log, no state change. Four intents
            // crossed the boundary that way, because all four did.
            //
            // The coordinator now translates each of them explicitly, so an
            // unrecognised intent means someone added one and forgot the
            // mapping. That is a programming error, and the cheapest thing it can
            // do is say so out loud instead of swallowing the click.
            //
            // Removing the `else` entirely would have the compiler enforce it,
            // but `TaskTimeSlotIntent` and `TaskDetailIntent.Domain` still share
            // a sealed root (`TaskDetailSlotIntent`), so exhaustiveness here would
            // demand a branch for every intent in the *task* hierarchy too —
            // which is the overlap this fix is removing, not something to settle
            // in the same change.
            else -> error(
                "TaskTimeSlot received an intent it cannot handle: ${intent::class.simpleName}. " +
                    "The coordinator must translate TaskDetailIntent.Domain.* into TaskTimeSlotIntent.*.",
            )
        }
    }

    private fun start() {
        val current = _state.value
        if (current is TaskTimeSlotState.Running) return
        // Clear the previous refusal so a retry is not blocked by it, and so the
        // next successful emission (or the next failure) decides the state.
        if (current is TaskTimeSlotState.Error) _state.value = TaskTimeSlotState.Idle
        scope.launch {
            timeTrackingRepo.startEntry(
                taskId = taskId,
                userId = currentUser.scopedUserId.value,
                kind = TimeEntryKind.Work,
                source = TimeEntrySource.Timer,
            ).onFailure { error ->
                // Not "the UI updates from the flow": when the *write* fails there
                // is nothing for the flow to update from, and the chip would sit
                // on Start forever with no log and no event. Three identical
                // comments in one file is the smell that produced this — one
                // wrong sentence, copied three times.
                _state.value = TaskTimeSlotState.Error(
                    error.message ?: "Не удалось запустить таймер",
                )
            }
        }
    }

    private fun stop() {
        if (_state.value is TaskTimeSlotState.Error) _state.value = TaskTimeSlotState.Idle
        scope.launch {
            timeTrackingRepo.stopEntry(currentUser.scopedUserId.value)
                .onFailure { error ->
                    _state.value = TaskTimeSlotState.Error(
                        error.message ?: "Не удалось остановить таймер",
                    )
                }
        }
    }

    private fun createManual(intent: TaskTimeSlotIntent.CreateManual) {
        if (_state.value is TaskTimeSlotState.Error) _state.value = TaskTimeSlotState.Idle
        scope.launch {
            timeTrackingRepo.createManualEntry(
                taskId = taskId,
                userId = currentUser.scopedUserId.value,
                startedAt = intent.startedAtMs,
                endedAt = intent.endedAtMs,
                kind = intent.kind,
                note = intent.note,
            ).onFailure { error ->
                _state.value = TaskTimeSlotState.Error(
                    error.message ?: "Не удалось сохранить запись времени",
                )
            }
        }
    }

    private fun tick(elapsedMs: Long) {
        val current = _state.value
        if (current is TaskTimeSlotState.Running) {
            _state.value = current.copy(elapsedMs = elapsedMs)
        }
    }
}
