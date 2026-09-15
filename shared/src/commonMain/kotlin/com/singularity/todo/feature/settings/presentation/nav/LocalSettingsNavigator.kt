package com.singularity.todo.feature.settings.presentation.nav

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal for [SettingsNavigator] — gives screens inside the settings
 * nested graph a type-safe way to trigger cross-feature navigation without
 * threading callbacks through every composable.
 *
 * The only cross-feature hop from settings is to [ProfileSwitcher][com.singularity.todo.feature.nav.AppDestination.ProfileSwitcher].
 */
val LocalSettingsNavigator = compositionLocalOf<SettingsNavigator> {
    error("SettingsNavigator not provided — wrap with SettingsNavGraph")
}
