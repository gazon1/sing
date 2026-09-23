package com.singularity.todo.core.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow

/**
 * Contract for appearance settings.
 */
interface AppearanceSettingsRepository {

    val darkTheme: Flow<Boolean>
    val accentColor: Flow<String>
    val fontSizeScale: Flow<Float>

    suspend fun setDarkTheme(value: Boolean)
    suspend fun setAccentColor(value: String)
    suspend fun setFontSizeScale(value: Float)
}

/**
 * Production [AppearanceSettingsRepository] backed by DataStore.
 */
class DataStoreAppearanceSettingsRepository(dataStore: DataStore<Preferences>) :
    BaseSettingsRepository(dataStore),
    AppearanceSettingsRepository {

    private val darkThemePref = boolPref(
        nsKey(SettingsNamespace.APPEARANCE, "dark_theme"),
        SettingsDefaults.Appearance.DARK_THEME,
    )
    private val accentColorPref = stringPref(
        nsKey(SettingsNamespace.APPEARANCE, "accent_color"),
        SettingsDefaults.Appearance.ACCENT_COLOR,
    )
    private val fontSizeScalePref = floatPref(
        nsKey(SettingsNamespace.APPEARANCE, "font_size_scale"),
        SettingsDefaults.Appearance.FONT_SIZE_SCALE,
    )

    override val darkTheme: Flow<Boolean> get() = darkThemePref.flow
    override val accentColor: Flow<String> get() = accentColorPref.flow
    override val fontSizeScale: Flow<Float> get() = fontSizeScalePref.flow

    override suspend fun setDarkTheme(value: Boolean) = darkThemePref.set(value)
    override suspend fun setAccentColor(value: String) = accentColorPref.set(value)
    override suspend fun setFontSizeScale(value: Float) = fontSizeScalePref.set(value)
}
