@file:Suppress("NoRealDelayInTest")

package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * JVM implementation of [PomodoroTimer].
 *
 * Uses an injected [CoroutineScope] for the timer loop — mirrors the [AndroidPomodoroTimer]
 * pattern but without OS-level alarms (AlarmManager is Android-only).
 *
 * ## Canonical VM pattern
 * Uses injected [CoroutineScope] for testability. Consumers obtain an instance via Koin.
 */
class JvmPomodoroTimer(
    private val clock: Clock,
    private val taskListProvider: PomodoroTaskListProvider,
    override val config: PomodoroConfig,
    private val scope: CoroutineScope,
    private val timeTrackingRepo: TimeTrackingRepository,
    private val currentUser: ProfileAwareCurrentUser,
) : PomodoroTimer {

    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<PomodoroState> = _state.asStateFlow()

    private var tickerJob: Job? = null

    override fun start(taskId: String?) {
        if (_state.value.isRunning) return
        val now = clock.now().toEpochMilliseconds()
        val dur = config.phaseSecondsOf(_state.value.phase)
        _state.value = _state.value.copy(
            isRunning = true,
            taskId = taskId,
            remainingSeconds = dur,
            phaseDurationSeconds = dur,
            phaseStartedAtEpochMs = now,
        )
        startTicker()
    }

    override fun pause() {
        tickerJob?.cancel()
        val now = clock.now().toEpochMilliseconds()
        val remaining = recomputeRemaining(_state.value, now)
        _state.value = _state.value.copy(
            isRunning = false,
            remainingSeconds = remaining,
            phaseStartedAtEpochMs = null,
        )
    }

    override fun resume() {
        val s = _state.value
        if (s.isRunning || s.phaseStartedAtEpochMs != null || s.remainingSeconds <= 0) return
        // Reconstruct phaseStartedAtEpochMs from remaining time
        val now = clock.now().toEpochMilliseconds()
        val startedAt = now - (s.phaseDurationSeconds - s.remainingSeconds) * 1000L
        _state.value = s.copy(isRunning = true, phaseStartedAtEpochMs = startedAt)
        startTicker()
    }

    override fun stop() {
        tickerJob?.cancel()
        _state.value = initialState()
    }

    override fun skip() {
        tickerJob?.cancel()
        onPhaseComplete()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (_state.value.isRunning) {
                delay(1000)
                val now = clock.now().toEpochMilliseconds()
                val remaining = recomputeRemaining(_state.value, now)
                if (remaining <= 0) {
                    _state.value = _state.value.copy(remainingSeconds = 0)
                    onPhaseComplete()
                    break
                }
                _state.value = _state.value.copy(remainingSeconds = remaining)
            }
        }
    }

    private fun onPhaseComplete() {
        val previousState = _state.value
        // Log time entry BEFORE transitioning state
        if (previousState.phase == PomodoroPhase.Work) {
            val taskId = previousState.taskId
            val startedAt = previousState.phaseStartedAtEpochMs
            if (taskId != null && startedAt != null) {
                val endedAt = clock.now().toEpochMilliseconds()
                scope.launch {
                    timeTrackingRepo.createManualEntry(
                        taskId = TaskId(taskId),
                        userId = currentUser.scopedUserId.value,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        kind = TimeEntryKind.Work,
                        note = null,
                        source = TimeEntrySource.Pomodoro,
                    )
                }
            }
        }
        _state.value = previousState.copy(isRunning = false, phaseStartedAtEpochMs = null)
        _state.value = nextPhase(_state.value, config)
    }

    private fun initialState() = PomodoroState(
        remainingSeconds = config.phaseSecondsOf(PomodoroPhase.Work),
        phaseDurationSeconds = config.phaseSecondsOf(PomodoroPhase.Work),
    )
}

/**
 * Recomputes remaining seconds from phase start time.
 * Mirrors [AndroidPomodoroTimer.recomputeRemaining].
 */
private fun recomputeRemaining(state: PomodoroState, nowEpochMs: Long): Int {
    val startedAt = state.phaseStartedAtEpochMs ?: return state.remainingSeconds
    val elapsed = ((nowEpochMs - startedAt) / 1000).toInt()
    return (state.phaseDurationSeconds - elapsed).coerceAtLeast(0)
}
