package com.singularity.todo

import androidx.compose.runtime.Composable
import com.singularity.todo.core.auth.AuthGuard
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.deeplink.DeepLinkTarget

/**
 * Root Composable — platform-specific actuals dispatch to the right shell.
 *
 * - androidMain: [App] → [PlatformShell] → [androidShellNav3] (bottom nav + FAB + NavDisplay)
 * - jvmMain: [App] → [PlatformShell] → [desktopShellNav3] (drawer + NavDisplay)
 *
 * [Nav3State] and [Navigator] are owned here so [LocalAppNavigator] can be provided
 * before [AuthGuard] — enabling [LoginScreen] (signed-out state) to read it.
 *
 * @param deepLinkTarget When non-null, the app opens that screen on the tab the
 *   target names. The target is resolved *before* the app starts (the registry
 *   turns an incoming URI into a target), so nothing here parses a URI.
 */
@Composable
expect fun App(deepLinkTarget: DeepLinkTarget?)

/**
 * Shell entry point that receives the navigation state built by [App].
 * Each platform actual calls its shell function with the passed state.
 */
@Composable
expect fun PlatformShell(state: Nav3State, navigator: Navigator, navCallbacks: NavCallbacks)

/**
 * Shared app content composable — state setup, navigation, and theme wrapper.
 * [App] on each platform wraps this in [AppVersionGateScreen] and [WhatsNewScreen].
 *
 * @param deepLinkTarget Screen to open on startup, or `null` for the start tab.
 * @param themeMode The colour scheme the user picked.
 * @param accent The accent colour the user picked.
 * @param fontSizeScale The user's text scale.
 */
@Composable
expect fun AppContent(
    deepLinkTarget: DeepLinkTarget?,
    themeMode: com.singularity.todo.core.settings.ThemeMode,
    accent: com.singularity.todo.core.ui.theme.SingularityAccents,
    fontSizeScale: Float,
)
