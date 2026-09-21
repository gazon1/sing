package com.singularity.todo.feature.pomodoro

import kotlinx.coroutines.flow.Flow

interface PomodoroRepository {
    fun observeAll(): Flow<List<PomodoroSession>>
    suspend fun save(session: PomodoroSession): Result<Unit>
}
