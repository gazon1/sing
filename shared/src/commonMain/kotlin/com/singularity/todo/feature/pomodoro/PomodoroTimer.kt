package com.singularity.todo.feature.pomodoro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.TaskRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PomodoroTimer(
    private val repository: PomodoroRepository,
    private val taskRepository: TaskRepository,
    private val currentUser: CurrentUser,
    private val config: PomodoroConfig = PomodoroConfig(),
) : ViewModel() {

    private val _state = MutableStateFlow(PomodoroState(remainingSeconds = config.workMinutes * 60))
    val state: StateFlow<PomodoroState> = _state.asStateFlow()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            val uid = currentUser.current
            taskRepository.watchTasks(uid, TaskFilter.Inbox).collect { _tasks.value = it }
        }
    }

    fun start(taskId: String? = null) {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true, taskId = taskId)
        timerJob = viewModelScope.launch {
            while (_state.value.isRunning && _state.value.remainingSeconds > 0) {
                delay(1000)
                _state.value = _state.value.copy(remainingSeconds = _state.value.remainingSeconds - 1)
            }
            if (_state.value.remainingSeconds <= 0) {
                onPhaseComplete()
            }
        }
    }

    fun pause() {
        timerJob?.cancel()
        _state.value = _state.value.copy(isRunning = false)
    }

    fun resume() {
        if (!_state.value.isRunning && _state.value.remainingSeconds > 0) {
            start(_state.value.taskId)
        }
    }

    fun stop() {
        timerJob?.cancel()
        _state.value = PomodoroState(remainingSeconds = config.workMinutes * 60)
    }

    fun skip() {
        timerJob?.cancel()
        onPhaseComplete()
    }

    private fun onPhaseComplete() {
        val current = _state.value
        viewModelScope.launch {
            // Save completed session
            repository.saveSession(
                PomodoroSession(
                    taskId = current.taskId,
                    phase = current.phase.name,
                    startedAt = System.currentTimeMillis() - (config.workMinutes * 60 * 1000L),
                    endedAt = System.currentTimeMillis(),
                    completedCycles = current.completedCycles,
                )
            )
        }
        // Transition to next phase
        _state.value = nextPhase(current, config)
    }
}
