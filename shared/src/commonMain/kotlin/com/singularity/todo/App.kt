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
 *
 * @param deeplinkViewId When non-null (Android only), navigates directly to the
 *   saved agenda edit screen on first composition. Null on JVM.
 * @param deeplinkTaskId When non-null (Android only), navigates directly to the
 *   task detail screen on first composition. Null on JVM.
 * @param deeplinkMagicEmail When non-null (Android only), opens the magic link
 *   confirm screen pre-filled with this email. Null on JVM.
 * @param deeplinkMagicToken When non-null (Android only), auto-submits verification
 *   for this token after navigating to the magic link confirm screen. Null on JVM.
 */
@Composable
expect fun App(
    deeplinkViewId: String?,
    deeplinkTaskId: String?,
    deeplinkMagicEmail: String?,
    deeplinkMagicToken: String?,
)

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
 * @param deeplinkViewId  Android-only deep-link: direct saved-agenda edit. Null on JVM.
 * @param deeplinkTaskId  Android-only deep-link: direct task detail. Null on JVM.
 * @param deeplinkMagicEmail  Magic-link email — supported on both platforms.
 * @param deeplinkMagicToken  Magic-link token — supported on both platforms.
 */
@Composable
expect fun AppContent(
    deeplinkViewId: String?,
    deeplinkTaskId: String?,
    deeplinkMagicEmail: String?,
    deeplinkMagicToken: String?,
    themeMode: com.singularity.todo.core.settings.ThemeMode,
    accent: com.singularity.todo.core.ui.theme.SingularityAccents,
    fontSizeScale: Float,
)
