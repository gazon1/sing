package com.singularity.todo.feature.pomodoro

import kotlinx.coroutines.flow.Flow

interface PomodoroRepository {
    fun watchSessions(): Flow<List<PomodoroSession>>
    suspend fun saveSession(session: PomodoroSession): Result<Unit>
}
