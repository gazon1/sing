package com.singularity.todo.feature.pomodoro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.pomodoro.recomputeRemaining
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
 * [phaseStartedAtEpochMs] stores the wall-clock time when the phase started,
 * enabling accurate remaining time recomputation after resume.
 */
class AndroidPomodoroTimer(
    private val clock: Clock,
    private val taskRepository: TaskRepository,
    private val alarmScheduler: PomodoroAlarmScheduler,
    override val config: PomodoroConfig = PomodoroConfig(),
) : ViewModel(),
    PomodoroTimer {

    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<PomodoroState> = _state.asStateFlow()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private var tickerJob: Job? = null
    // Race guard: both in-app ticker and OS alarm can call onPhaseComplete simultaneously
    private val phaseEnded = AtomicBoolean(false)

    init {
        // Defensive cancel: a stale alarm from a previous process instance must not fire
        alarmScheduler.cancelPhaseEndAlarm()
        viewModelScope.launch {
            taskRepository.observeByFilter(TaskFilter.Inbox).collect { _tasks.value = it }
        }
    }

    override fun start(taskId: String?) {
        if (_state.value.isRunning) return
        phaseEnded.set(false)
        val now = clock.now().toEpochMilliseconds()
        val dur = config.phaseSecondsOf(_state.value.phase)
        _state.value = _state.value.copy(
            isRunning = true,
            taskId = taskId,
            remainingSeconds = dur,
            phaseDurationSeconds = dur,
            phaseStartedAtEpochMs = now,
        )
        alarmScheduler.schedulePhaseEnd(now + dur * 1000L, taskId, _state.value.phase.name)
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
        alarmScheduler.schedulePhaseEnd(startedAt + s.phaseDurationSeconds * 1000L, s.taskId, s.phase.name)
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
        tickerJob = viewModelScope.launch {
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
        if (!phaseEnded.compareAndSet(false, true)) return
        _state.value = _state.value.copy(isRunning = false, phaseStartedAtEpochMs = null)
        _state.value = nextPhase(_state.value, config)
    }

    private fun initialState() = PomodoroState(
        remainingSeconds = config.phaseSecondsOf(PomodoroPhase.Work),
        phaseDurationSeconds = config.phaseSecondsOf(PomodoroPhase.Work),
    )
}
