package com.singularity.todo.core.appearance

import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Reads and writes appearance settings.
 *
 * Contributes [SettingsSection.Appearance] to the unified settings UI.
 */
class AppearanceSettingsStore(
    private val appearance: AppearanceSettingsRepository,
) {
    /**
     * Appearance settings section — all 3 fields from [AppearanceSettingsRepository].
     */
    fun observe(): Flow<SettingsSection.Appearance> = combine(
        appearance.darkTheme,
        appearance.accentColor,
        appearance.fontSizeScale,
    ) { darkTheme, accentColor, fontSizeScale ->
        SettingsSection.Appearance(
            darkTheme = darkTheme,
            accentColor = accentColor,
            fontSizeScale = fontSizeScale,
        )
    }

    suspend fun process(intent: SettingsIntent.Appearance) {
        when (intent) {
            is SettingsIntent.Appearance.UpdateDarkTheme -> appearance.setDarkTheme(intent.value)
            is SettingsIntent.Appearance.UpdateAccentColor -> appearance.setAccentColor(intent.value)
            is SettingsIntent.Appearance.UpdateFontSizeScale -> appearance.setFontSizeScale(intent.value)
        }
    }
}
