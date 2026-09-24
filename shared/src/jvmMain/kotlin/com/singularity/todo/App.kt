package com.singularity.todo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.singularity.todo.core.appearance.AppearanceSettingsRepository
import com.singularity.todo.core.auth.AuthGuard
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.gate.presentation.screen.AppVersionGateScreen
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.rememberNav3State
import com.singularity.todo.feature.whatsnew.presentation.screen.WhatsNewScreen
import com.singularity.todo.shell.DesktopShellNav3Root
import org.koin.compose.koinInject
import java.awt.Desktop
import java.net.URI

private const val RELEASES_URL = "https://github.com/singularity-todo/singularity/releases"

/**
 * JVM Desktop actual implementation of [App].
 *
 * Renders [AppVersionGateScreen] first — the gate checks [RemoteConfigPort]
 * and shows a blocked UI if the running version is too old. The gate handles
 * its own "Check Again" retry. When the gate passes, it renders [AppContent].
 *
 * @param deeplinkViewId Ignored on JVM — notifications are not supported.
 * @param deeplinkTaskId Ignored on JVM — calendar deep-links are Android-only.
 */
@Composable
actual fun App(deeplinkViewId: String?, deeplinkTaskId: String?) {
    val appearance: AppearanceSettingsRepository = koinInject()
    val darkTheme by appearance.darkTheme.collectAsState(initial = false)
    val accentName by appearance.accentColor.collectAsState(initial = "blue")
    val accent = SingularityAccents.fromString(accentName)
    val fontSizeScale by appearance.fontSizeScale.collectAsState(initial = 1f)

    AppVersionGateScreen(
        playStoreUrl = RELEASES_URL,
        onOpenStore = {
            Desktop.getDesktop().browse(URI(RELEASES_URL))
        },
        modifier = Modifier,
        content = {
            AppContent(
                darkTheme = darkTheme,
                accent = accent,
                fontSizeScale = fontSizeScale,
            )
            WhatsNewScreen(
                onDismiss = { /* caller is the screen; no extra action needed */ },
                modifier = Modifier,
            )
        },
    )
}

@Composable
private fun AppContent(darkTheme: Boolean, accent: SingularityAccents, fontSizeScale: Float) {
    val state = rememberNav3State()
    val navigator = remember(state) { Navigator(state) }
    val navCallbacks = remember(navigator) {
        NavCallbacks(navigate = navigator::navigate, goBack = navigator::goBack)
    }

    SingularityTheme(darkTheme = darkTheme, accent = accent, fontSizeScale = fontSizeScale) {
        CompositionLocalProvider(
            LocalAppNavigator provides navCallbacks,
        ) {
            AuthGuard {
                PlatformShell(state, navigator, navCallbacks)
            }
        }
    }
}

/**
 * JVM Desktop actual implementation of [PlatformShell].
 */
@Composable
actual fun PlatformShell(state: Nav3State, navigator: Navigator, navCallbacks: NavCallbacks) {
    DesktopShellNav3Root(state, navigator, navCallbacks)
}
