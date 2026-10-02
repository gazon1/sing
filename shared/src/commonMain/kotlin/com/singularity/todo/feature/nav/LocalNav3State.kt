package com.singularity.todo.feature.nav

import androidx.compose.runtime.compositionLocalOf

/**
 * Provides the app-wide [Nav3State] to any screen that needs to observe tab-reselect events.
 *
 * Placed at the shell level (alongside [LocalAppNavigator]) so screens deep inside a nested
 * graph can reach it without threading the state through every entry provider.
 *
 * Only [TabReselectScrollReset] should read this. Navigation itself goes through
 * [LocalAppNavigator] or the per-graph `LocalXxxNavigator` — reaching into [Nav3State] to
 * mutate `topLevelRoute` bypasses the back-stack bookkeeping [Navigator] performs.
 */
val LocalNav3State = compositionLocalOf<Nav3State> {
    error("Nav3State not provided — wrap with the shell (DesktopShellNav3 / AppShell)")
}
