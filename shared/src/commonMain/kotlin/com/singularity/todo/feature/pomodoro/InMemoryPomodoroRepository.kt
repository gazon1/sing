package com.singularity.todo.feature.pomodoro

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryPomodoroRepository : PomodoroRepository {
    private val _sessions = MutableStateFlow<List<PomodoroSession>>(emptyList())

    override fun observeAll(): Flow<List<PomodoroSession>> = _sessions

    override suspend fun save(session: PomodoroSession): Result<Unit> = runCatching {
        _sessions.value += session
    }
}
