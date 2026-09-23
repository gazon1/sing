package com.singularity.todo.core.schedule

import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow

/**
 * Contributes the Greeting settings section to the unified settings UI.
 *
 * Registration: `single<SettingsContributor> { GreetingSettingsContributor(get()) }`.
 */
class GreetingSettingsContributor(
    private val store: GreetingSettingsStore,
) : SettingsContributor<SettingsSection.Greeting, SettingsIntent.Greeting> {

    override val section: SettingsSection.Greeting = SettingsSection.Greeting()

    override fun observe(): Flow<SettingsSection.Greeting> = store.observe()

    override suspend fun process(intent: SettingsIntent.Greeting) {
        store.process(intent)
    }
}
