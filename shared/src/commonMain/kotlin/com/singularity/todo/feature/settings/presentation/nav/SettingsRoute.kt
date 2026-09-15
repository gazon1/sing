package com.singularity.todo.feature.settings.presentation.nav

import androidx.navigation3.runtime.NavKey

/**
 * Navigation route for the settings nested graph.
 *
 * Lives inside [SettingsNavGraph] which provides its own NavBackStack.
 * Single-route — tabs are local `var selectedTab by remember` state inside
 * [SettingsContent][com.singularity.todo.feature.settings.SettingsContent].
 * NOT @Serializable — the nested graph uses an empty SavedStateConfiguration.
 */
data object Settings : NavKey
