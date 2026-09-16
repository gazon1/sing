package com.singularity.todo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.singularity.todo.core.auth.AuthGuard
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.rememberNav3State
import com.singularity.todo.shell.DesktopShellNav3Root
import org.koin.compose.koinInject

/**
 * JVM Desktop actual implementation of [App].
 * Builds navigation state and provides [LocalAppNavigator] before calling [PlatformShell].
 */
@Composable
actual fun App() {
    val settings: SettingsRepository = koinInject()
    val darkTheme by settings.darkTheme.collectAsState(initial = false)
    val accentName by settings.accentColor.collectAsState(initial = "blue")
    val accent = SingularityAccents.fromString(accentName)

    val state = rememberNav3State()
    val navigator = remember(state) { Navigator(state) }
    val navCallbacks = remember(navigator) {
        NavCallbacks(navigate = navigator::navigate, goBack = navigator::goBack)
    }

    SingularityTheme(darkTheme = darkTheme, accent = accent) {
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
 * Delegates to the renamed [DesktopShellNav3Root] which owns the UI chrome.
 */
@Composable
actual fun PlatformShell(state: Nav3State, navigator: Navigator, navCallbacks: NavCallbacks) {
    DesktopShellNav3Root(state, navigator, navCallbacks)
}
