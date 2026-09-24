package com.singularity.todo.core.appearance

import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow

/**
 * Marker interface for the Appearance settings contributor.
 * Used by [SettingsViewModel] to resolve the contributor without type erasure.
 *
 * Registration: `single<SettingsContributor<*, *>> { AppearanceSettingsContributor(get()) }`.
 */
interface AppearanceContributor : SettingsContributor<SettingsSection.Appearance, SettingsIntent.Appearance>

/**
 * Contributes the Appearance settings section to the unified settings UI.
 */
class AppearanceSettingsContributor(private val store: AppearanceSettingsStore) : AppearanceContributor {

    override val section: SettingsSection.Appearance = SettingsSection.Appearance()

    override fun observe(): Flow<SettingsSection.Appearance> = store.observe()

    override suspend fun process(intent: SettingsIntent.Appearance) {
        store.process(intent)
    }
}
