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

    /**
     * The version of the recurring-series repair last completed in [profileScope],
     * or `0` when it has never run there.
     *
     * The key is per profile because the repair is: a profile is a separate set of
     * tasks, and a launch that repairs one profile says nothing about the legacy
     * rows another profile may still hold. It is a version rather than a boolean
     * so a future build that can create a new legacy shape bumps
     * [com.singularity.todo.feature.tasks.domain.usecase.RepairRecurringSeriesUseCase.VERSION]
     * and the pass runs once more.
     */
    fun recurringSeriesRepairVersion(profileScope: String): Flow<Int>

    suspend fun setRecurringSeriesRepairVersion(profileScope: String, version: Int)
}
