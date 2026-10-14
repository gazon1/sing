package com.singularity.todo.core.tasks

import kotlinx.coroutines.flow.Flow

/**
 * Contract for task-related settings such as the staleness threshold.
 *
 * The production implementation is [com.singularity.todo.core.settings.SettingsRepository],
 * which owns the DataStore keys for all task settings. The interface exists so that
 * [TasksSettingsStore] can depend on it without coupling to the full SettingsRepository
 * graph, and to mirror the [NotificationsSettingsRepository] / [WorkScheduleSettingsRepository]
 * pattern used by other sections.
 */
interface TasksSettingsRepository {

    /** Days after which an undated, incomplete task is considered stagnant. */
    val stalenessThresholdDays: Flow<Int>

    suspend fun setStalenessThresholdDays(value: Int)
}
