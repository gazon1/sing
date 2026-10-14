package com.singularity.todo.core.tasks

import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow

/**
 * Marker interface for the Tasks settings contributor.
 * Used by [com.singularity.todo.feature.settings.SettingsViewModel] to resolve the
 * contributor without type erasure.
 */
interface TasksContributor : SettingsContributor<SettingsSection.Tasks, SettingsIntent.Tasks>

/**
 * Contributes the Tasks settings section to the unified settings UI.
 *
 * @param store reads and writes task-related settings such as the staleness threshold.
 */
class TasksSettingsContributor(private val store: TasksSettingsStore) : TasksContributor {

    override val section: SettingsSection.Tasks = SettingsSection.Tasks()

    override fun observe(): Flow<SettingsSection.Tasks> = store.observe()

    override suspend fun process(intent: SettingsIntent.Tasks) {
        store.process(intent)
    }
}
