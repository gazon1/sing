package com.singularity.todo.core.schedule

import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Reads and writes greeting hour settings.
 *
 * Contributes [SettingsSection.Greeting] to the unified settings UI.
 * Registration: `single<SettingsContributor> { GreetingSettingsContributor(get()) }`.
 */
class GreetingSettingsStore(
    private val greeting: GreetingSettingsRepository,
) {
    /**
     * Greeting settings section — morning and afternoon hour boundaries.
     */
    fun observe(): Flow<SettingsSection.Greeting> = combine(
        greeting.morningEndHour,
        greeting.afternoonEndHour,
    ) { morningEnd, afternoonEnd ->
        SettingsSection.Greeting(
            morningEndHour = morningEnd,
            afternoonEndHour = afternoonEnd,
        )
    }

    suspend fun process(intent: SettingsIntent.Greeting) {
        when (intent) {
            is SettingsIntent.Greeting.UpdateMorningEnd -> greeting.setMorningEndHour(intent.hour)
            is SettingsIntent.Greeting.UpdateAfternoonEnd -> greeting.setAfternoonEndHour(intent.hour)
        }
    }
}
