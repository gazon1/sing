package com.singularity.todo.feature.nav

import kotlinx.serialization.Serializable

/**
 * Navigation route for the settings nested graph.
 *
 * Lives inside [SettingsNavGraph] which provides its own NavBackStack.
 * Single-route — tabs are local `var selectedTab by remember` state inside
 * [SettingsContent][com.singularity.todo.feature.settings.SettingsContent].
 *
 * `@Serializable` so that `SettingsNavGraph.jvm` can register it in the
 * polymorphic `NavKey` serializers module required by
 * `rememberNavBackStack(SavedStateConfiguration, ...)`.
 */
@Serializable
data object Settings : AppNavKey
