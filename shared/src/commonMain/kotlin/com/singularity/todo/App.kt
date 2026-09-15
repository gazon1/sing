package com.singularity.todo

import androidx.compose.runtime.Composable
import com.singularity.todo.core.auth.AuthGuard
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator

/**
 * Root Composable — platform-specific actuals dispatch to the right shell.
 *
 * - androidMain: [App] → [PlatformShell] → [androidShellNav3] (bottom nav + FAB + NavDisplay)
 * - jvmMain: [App] → [PlatformShell] → [desktopShellNav3] (drawer + NavDisplay)
 *
 * [Nav3State] and [Navigator] are owned here so [LocalAppNavigator] can be provided
 * before [AuthGuard] — enabling [LoginScreen] (signed-out state) to read it.
 */
expect @Composable
fun App()

/**
 * Shell entry point that receives the navigation state built by [App].
 * Each platform actual calls its shell function with the passed state.
 */
expect @Composable
fun PlatformShell(
    state: Nav3State,
    navigator: Navigator,
    navCallbacks: NavCallbacks,
)
