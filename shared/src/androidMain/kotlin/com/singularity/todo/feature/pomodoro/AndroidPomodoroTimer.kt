package com.singularity.todo.feature.pomodoro

import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.pomodoro.recomputeRemaining
import com.singularity.todo.feature.timetracking.TimeEntryKind
import com.singularity.todo.feature.timetracking.TimeEntrySource
import com.singularity.todo.feature.timetracking.data.TimeTrackingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Hybrid Pomodoro timer: in-app 1 Hz ticker + OS-level [AlarmManager.setAlarmClock].
 *
 * ## Why hybrid?
 * - Pure in-app ticker dies when the process is killed.
 * - OS-level alarm alone gives poor UX (only updated on fire, no smooth countdown).
 *
 * The 1 Hz [delay] loop drives the UI countdown. [PomodoroAlarmScheduler] is a
 * safety net that fires even after process death, ensuring phase transitions
 * are never lost.
 *
 * ## Race guard
 * [phaseEnded] prevents double-trigger when both the in-app ticker and the OS
 * alarm fire simultaneously (both call [onPhaseComplete]).
 *
 * ## Pause/resume
 * [PomodoroState.phaseStartedAtEpochMs] stores the wall-clock time when the phase started,
 * enabling accurate remaining time recomputation after resume.
 *
 * ## Canonical VM pattern
 * Does NOT extend [ViewModel]. Uses injected [CoroutineScope] for testability.
 * Consumers (e.g. [PomodoroScreen]) obtain an instance via Koin.
 */
class AndroidPomodoroTimer(
    private val clock: Clock,
    private val taskListProvider: PomodoroTaskListProvider,
    private val alarmScheduler: PomodoroScheduler,
    override val config: PomodoroConfig,
    private val scope: CoroutineScope,
    private val timeTrackingRepo: TimeTrackingRepository,
    private val currentUser: ProfileAwareCurrentUser,
) : PomodoroTimer {

    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<PomodoroState> = _state.asStateFlow()

    private var tickerJob: Job? = null

    /**
     * Race guard: prevents double-trigger when OS alarm fires simultaneously with in-app ticker.
     * Replaces [java.util.concurrent.atomic.AtomicBoolean] — safe for single-threaded coroutine access.
     */
    @Volatile private var phaseEnded = false

    init {
        // Defensive cancel: a stale alarm from a previous process instance must not fire
        alarmScheduler.cancelPhaseEndAlarm()
    }

    override fun start(taskId: String?) {
        if (_state.value.isRunning) return
        phaseEnded = false
        val now = clock.now().toEpochMilliseconds()
        val dur = config.phaseSecondsOf(_state.value.phase)
        _state.value = _state.value.copy(
            isRunning = true,
            taskId = taskId,
            remainingSeconds = dur,
            phaseDurationSeconds = dur,
            phaseStartedAtEpochMs = now,
        )
        alarmScheduler.schedulePhaseEnd(now + dur * 1000L, taskId, _state.value.phase)
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
        alarmScheduler.cancelPhaseEndAlarm()
    }

    override fun resume() {
        val s = _state.value
        if (s.isRunning || s.phaseStartedAtEpochMs != null || s.remainingSeconds <= 0) return
        // Reconstruct phaseStartedAtEpochMs from remaining time
        val now = clock.now().toEpochMilliseconds()
        val startedAt = now - (s.phaseDurationSeconds - s.remainingSeconds) * 1000L
        _state.value = s.copy(isRunning = true, phaseStartedAtEpochMs = startedAt)
        alarmScheduler.schedulePhaseEnd(startedAt + s.phaseDurationSeconds * 1000L, s.taskId, s.phase)
        startTicker()
    }

    override fun stop() {
        tickerJob?.cancel()
        alarmScheduler.cancelPhaseEndAlarm()
        _state.value = initialState()
    }

    override fun skip() {
        tickerJob?.cancel()
        alarmScheduler.cancelPhaseEndAlarm()
        onPhaseComplete()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (_state.value.isRunning) {
                delay(1000.milliseconds)
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
        // Race guard: prevents double-trigger when OS alarm fires simultaneously with in-app ticker
        if (phaseEnded) return
        phaseEnded = true
        val previousState = _state.value
        // Log time entry BEFORE transitioning state — taskId and phaseStartedAtEpochMs are still valid here
        if (previousState.phase == PomodoroPhase.Work) {
            val taskId = previousState.taskId
            val startedAt = previousState.phaseStartedAtEpochMs
            if (taskId != null && startedAt != null) {
                val endedAt = clock.now().toEpochMilliseconds()
                scope.launch {
                    timeTrackingRepo.createManualEntry(
                        taskId = com.singularity.todo.feature.tasks.domain.model.TaskId(taskId),
                        userId = currentUser.scopedUserId.value,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        kind = TimeEntryKind.Work,
                        note = null,
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
