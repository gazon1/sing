package com.singularity.todo.core.tasks

import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reads and writes task-related settings.
 *
 * Contributes [SettingsSection.Tasks] to the unified settings UI.
 *
 * @param repository The [TasksSettingsRepository] (implemented by [SettingsRepository])
 *                   that provides the raw staleness threshold flow and setter.
 */
class TasksSettingsStore(private val repository: TasksSettingsRepository) {

    /**
     * Tasks settings section — currently just the staleness threshold.
     */
    fun observe(): Flow<SettingsSection.Tasks> =
        repository.stalenessThresholdDays
            .map { stalenessThresholdDays ->
                SettingsSection.Tasks(
                    stalenessThresholdDays = stalenessThresholdDays,
                )
            }

    suspend fun process(intent: SettingsIntent.Tasks) {
        when (intent) {
            is SettingsIntent.Tasks.UpdateStalenessThresholdDays ->
                repository.setStalenessThresholdDays(intent.value)
        }
    }
}
