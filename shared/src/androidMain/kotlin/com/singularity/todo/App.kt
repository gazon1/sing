package com.singularity.todo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.singularity.todo.core.appearance.AppearanceSettingsRepository
import com.singularity.todo.core.auth.AuthGuard
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.rememberNav3State
import org.koin.compose.koinInject

/**
 * Android actual implementation of [App].
 * Builds navigation state and provides [LocalAppNavigator] before calling [PlatformShell].
 *
 * @param deeplinkViewId When non-null, the app navigates directly to
 *   [AppDestination.AgendaGraph] with [AgendaStartRoute.SavedAgendaEdit] on first composition.
 *   This handles notification taps that should open a specific saved agenda view.
 */
@Composable
actual fun App(deeplinkViewId: String?) {
    val appearance: AppearanceSettingsRepository = koinInject()
    val darkTheme by appearance.darkTheme.collectAsState(initial = false)
    val accentName by appearance.accentColor.collectAsState(initial = "blue")
    val accent = SingularityAccents.fromString(accentName)
    val fontSizeScale by appearance.fontSizeScale.collectAsState(initial = 1f)

    val state = rememberNav3State()
    val navigator = remember(state) { Navigator(state) }
    val navCallbacks = remember(navigator) {
        NavCallbacks(navigate = navigator::navigate, goBack = navigator::goBack)
    }

    // Handle notification deeplink: navigate to the saved agenda view on first composition
    LaunchedEffect(deeplinkViewId, navigator) {
        if (deeplinkViewId != null) {
            navigator.navigate(AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaEdit(deeplinkViewId)))
        }
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
 * Android actual implementation of [PlatformShell].
 * Delegates to the renamed [androidShellNav3Root] which owns the UI chrome.
 */
@Composable
actual fun PlatformShell(state: Nav3State, navigator: Navigator, navCallbacks: NavCallbacks) {
    androidShellNav3Root(state, navigator, navCallbacks)
}
