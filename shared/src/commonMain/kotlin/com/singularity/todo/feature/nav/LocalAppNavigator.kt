package com.singularity.todo.feature.nav

import androidx.compose.runtime.compositionLocalOf

/**
 * Provides the outer-app [NavCallbacks] to all screens, including those rendered
 * during the signed-out auth state (inside [AuthGuard]).
 *
 * Placed at the [App] level so it is available to:
 * - [LoginScreen] (reads it to navigate on [AuthUiEvent.NavigateToHome])
 * - [ArchiveScreen] (reads it to open task details)
 * - Every nested graph's screens (cross-feature hops without callback threading)
 *
 * Screens inside a nested graph can still use their own [LocalXxxNavigator] for
 * intra-graph navigation; they can additionally read [LocalAppNavigator] for
 * cross-graph navigation. This is intentional — the outer navigator is always
 * available as a fallback.
 */
val LocalAppNavigator = compositionLocalOf<NavCallbacks> {
    error("AppNavigator not provided — wrap with the shell")
}
