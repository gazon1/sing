package com.singularity.todo.core.schedule

import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow

/**
 * Marker interface for the Work Schedule settings contributor.
 * Used by [com.singularity.todo.feature.settings.SettingsViewModel] to resolve the
 * contributor without type erasure.
 */
interface WorkScheduleContributor : SettingsContributor<SettingsSection.WorkSchedule, SettingsIntent.WorkSchedule>

/**
 * Contributes the Work Schedule settings section to the unified settings UI.
 *
 * Registration: `single<SettingsContributor> { WorkScheduleSettingsContributor(get()) }`.
 */
class WorkScheduleSettingsContributor(
    private val store: WorkScheduleSettingsStore,
) : WorkScheduleContributor {

    override val section: SettingsSection.WorkSchedule = SettingsSection.WorkSchedule()

    override fun observe(): Flow<SettingsSection.WorkSchedule> = store.observe()

    override suspend fun process(intent: SettingsIntent.WorkSchedule) {
        store.process(intent)
    }
}
