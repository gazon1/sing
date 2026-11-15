package com.singularity.todo.core.tasks

import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Reads and writes task-related settings.
 *
 * Contributes [SettingsSection.Tasks] to the unified settings UI.
 *
 * @param repository The [TasksSettingsRepository] (implemented by [SettingsRepository])
 *                   that provides the raw values and setters.
 */
class TasksSettingsStore(private val repository: TasksSettingsRepository) {
    /** Tasks settings section: the staleness threshold and the link-preview switch. */
    fun observe(): Flow<SettingsSection.Tasks> =
        combine(
            repository.stalenessThresholdDays,
            repository.linkPreviewsEnabled,
        ) { stalenessThresholdDays, linkPreviewsEnabled ->
            SettingsSection.Tasks(
                stalenessThresholdDays = stalenessThresholdDays,
                linkPreviewsEnabled = linkPreviewsEnabled,
            )
        }

    suspend fun process(intent: SettingsIntent.Tasks) {
        when (intent) {
            is SettingsIntent.Tasks.UpdateStalenessThresholdDays -> {
                repository.setStalenessThresholdDays(intent.value)
            }

            is SettingsIntent.Tasks.UpdateLinkPreviewsEnabled -> {
                repository.setLinkPreviewsEnabled(intent.value)
            }
        }
    }
}
