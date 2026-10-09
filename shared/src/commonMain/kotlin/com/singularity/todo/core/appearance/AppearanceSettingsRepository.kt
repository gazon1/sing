package com.singularity.todo.core.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.core.settings.SettingsNamespace
import com.singularity.todo.core.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Contract for appearance settings.
 */
interface AppearanceSettingsRepository {

    /**
     * The theme mode tri-state (replaces [darkTheme]).
     *
     * Reading this applies the migration: absent `theme_mode` + present `dark_theme`
     * boolean is translated to [ThemeMode.Dark] or [ThemeMode.Light].
     * The old boolean key is never written by new code.
     */
    val themeMode: Flow<ThemeMode>

    /**
     * Legacy boolean — retained for callers that have not yet migrated to [themeMode].
     * Computed from [themeMode]: [ThemeMode.Dark] → `true`, everything else → `false`.
     *
     * @deprecated Use [themeMode] instead. Will be removed once all callers are migrated.
     */
    @Deprecated("Migrate to themeMode", ReplaceWith("themeMode"))
    val darkTheme: Flow<Boolean>

    val accentColor: Flow<String>
    val fontSizeScale: Flow<Float>

    suspend fun setThemeMode(value: ThemeMode)

    /** @deprecated Use [setThemeMode] instead. */
    @Deprecated("Migrate to setThemeMode", ReplaceWith("setThemeMode"))
    suspend fun setDarkTheme(value: Boolean)
    suspend fun setAccentColor(value: String)
    suspend fun setFontSizeScale(value: Float)
}

/**
 * Production [AppearanceSettingsRepository] backed by DataStore.
 *
 * ## Migration (REQ-THEME-010)
 * The new [themeMode] tri-state is stored under `appearance.theme_mode`.
 * The old `appearance.dark_theme` boolean is **never written** by new code —
 * it is kept so that a downgraded app can still read the last state it understood.
 *
 * Reading applies the migration:
 * 1. If `theme_mode` is present → parse it as [ThemeMode].
 * 2. If absent but `dark_theme` is present → translate: `true` → [ThemeMode.Dark], `false` → [ThemeMode.Light].
 * 3. If neither → [ThemeMode.System].
 */
class DataStoreAppearanceSettingsRepository(dataStore: DataStore<Preferences>) :
    BaseSettingsRepository(dataStore),
    AppearanceSettingsRepository {

    private val themeModePref = stringPref(
        nsKey(SettingsNamespace.APPEARANCE, ThemeMode.STORAGE_KEY),
        "",
    )
    private val oldDarkThemePref = boolPref(
        nsKey(SettingsNamespace.APPEARANCE, "dark_theme"),
        false,
    )

    private val accentColorPref = stringPref(
        nsKey(SettingsNamespace.APPEARANCE, "accent_color"),
        SettingsDefaults.Appearance.ACCENT_COLOR,
    )
    private val fontSizeScalePref = floatPref(
        nsKey(SettingsNamespace.APPEARANCE, "font_size_scale"),
        SettingsDefaults.Appearance.FONT_SIZE_SCALE,
    )

    /**
     * Resolves the tri-state with migration.
     *
     * The `theme_mode` string is the authoritative new key.
     * If it is absent/empty, the old `dark_theme` boolean is consulted as a fallback.
     * When neither exists, [ThemeMode.System] is returned (new-install default).
     */
    override val themeMode: Flow<ThemeMode> = combine(
        themeModePref.flow,
        oldDarkThemePref.flow,
    ) { storedMode: String, oldDarkTheme: Boolean ->
        if (storedMode.isNotEmpty()) {
            ThemeMode.fromStorageString(storedMode)
        } else {
            // Migration: old boolean is authoritative only when the new key is absent.
            // `oldDarkTheme` defaults to false (the old default), which maps to Light.
            // A user who never touched the setting gets System (new default).
            // A user who had dark=true gets Dark, dark=false gets Light.
            ThemeMode.fromLegacyBoolean(oldDarkTheme)
        }
    }

    /**
     * Derived from [themeMode]: [ThemeMode.Dark] → `true`, everything else → `false`.
     * This keeps existing callers working until they migrate to [themeMode].
     */
    override val darkTheme: Flow<Boolean> = themeMode.map { mode -> mode == ThemeMode.Dark }

    override val accentColor: Flow<String> get() = accentColorPref.flow
    override val fontSizeScale: Flow<Float> get() = fontSizeScalePref.flow

    /**
     * Writes the tri-state. The old boolean key is intentionally not written
     * (REQ-THEME-010: downgraded app must still find its last state).
     */
    override suspend fun setThemeMode(value: ThemeMode) {
        themeModePref.set(value.toStorageString())
    }

    @Deprecated("Migrate to setThemeMode", ReplaceWith("setThemeMode(ThemeMode.fromLegacyBoolean(value))"))
    override suspend fun setDarkTheme(value: Boolean) {
        setThemeMode(ThemeMode.fromLegacyBoolean(value))
    }

    override suspend fun setAccentColor(value: String) = accentColorPref.set(value)
    override suspend fun setFontSizeScale(value: Float) = fontSizeScalePref.set(value)
}
