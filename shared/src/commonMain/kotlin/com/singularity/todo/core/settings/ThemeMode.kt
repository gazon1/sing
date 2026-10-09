package com.singularity.todo.core.settings

/**
 * Theme mode setting — the user-facing tri-state replacing the legacy boolean.
 *
 * ## Migration from boolean (pre-#210)
 * - `true` (dark theme on) → [Dark]
 * - `false` (dark theme off) → [Light]
 * - absent → [System]
 *
 * The old boolean key is never rewritten so that a downgraded app can still read it.
 *
 * ## Storage
 * Stored as a lowercase string under `appearance.theme_mode`.
 * The old `appearance.dark_theme` boolean is left untouched.
 */
sealed class ThemeMode {
    /** Follow the operating system's light/dark setting. */
    data object System : ThemeMode() {
        override val name: String = "System"
    }

    /** Always use the light palette. */
    data object Light : ThemeMode() {
        override val name: String = "Light"
    }

    /** Always use the dark palette. */
    data object Dark : ThemeMode() {
        override val name: String = "Dark"
    }

    /** Stable identifier for testTag and similar non-translated references. */
    abstract val name: String

    /**
     * Serialises to the storage string.
     * Matching is case-insensitive on read; write produces lowercase.
     */
    fun toStorageString(): String = when (this) {
        System -> "system"
        Light -> "light"
        Dark -> "dark"
    }

    companion object {
        const val STORAGE_KEY = "theme_mode"

        fun fromStorageString(value: String): ThemeMode = when (value.lowercase()) {
            "dark" -> Dark
            "light" -> Light
            else -> System
        }

        /**
         * Translates a legacy boolean dark-theme preference.
         * `true` → [Dark], `false` → [Light].
         */
        fun fromLegacyBoolean(isDark: Boolean): ThemeMode = if (isDark) Dark else Light
    }
}
