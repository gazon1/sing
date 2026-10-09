package com.singularity.todo

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.singularity.todo.core.appearance.AppearanceSettingsRepository
import com.singularity.todo.core.auth.AuthGuard
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.LocalHaptic
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.gate.presentation.screen.AppVersionGateScreen
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.rememberNav3State
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.whatsnew.presentation.screen.WhatsNewScreen
import org.koin.compose.koinInject
import com.singularity.todo.core.ui.preview.noopClick

private const val PLAY_STORE_URI = "market://details?id=com.singularity.todo"

/**
 * Android actual implementation of [App].
 *
 * Renders [AppVersionGateScreen] first — the gate checks [RemoteConfigPort]
 * and shows a blocked UI if the running version is too old. The gate handles
 * its own "Check Again" retry. When the gate passes, it renders [AppContent].
 *
 * @param deeplinkViewId When non-null, the app navigates directly to
 *   [AppDestination.AgendaGraph] with [AgendaStartRoute.SavedAgendaEdit].
 * @param deeplinkTaskId When non-null, the app navigates directly to
 *   [AppDestination.TasksGraph] with [TasksStartRoute.Detail].
 */
@Composable
actual fun App(deeplinkViewId: String?, deeplinkTaskId: String?) {
    val appearance: AppearanceSettingsRepository = koinInject()
    val themeMode by appearance.themeMode.collectAsState(initial = ThemeMode.System)
    val accentName by appearance.accentColor.collectAsState(initial = "blue")
    val accent = SingularityAccents.fromString(accentName)
    val fontSizeScale by appearance.fontSizeScale.collectAsState(initial = 1f)

    // Capture context and build intent at composition time — LocalContext.current is @Composable.
    val context = LocalContext.current
    val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URI))

    AppVersionGateScreen(
        playStoreUrl = PLAY_STORE_URI,
        onOpenStore = {
            @Suppress("BatteryLife")
            context.startActivity(storeIntent)
        },
        modifier = Modifier
            .fillMaxSize()
            // Surfaces every Modifier.testTag as an accessibility resource-id, which is how
            // external UI automation (Maestro, UIAutomator) addresses the TestTags registry.
            // Without it, Compose keeps test tags in an internal-only semantics property and
            // the whole TestTags catalogue is invisible outside the app.
            .semantics { testTagsAsResourceId = true },
        content = {
            AppContent(
                deeplinkViewId = deeplinkViewId,
                deeplinkTaskId = deeplinkTaskId,
                themeMode = themeMode,
                accent = accent,
                fontSizeScale = fontSizeScale,
            )
            // WhatsNew is rendered as an overlay — it observes RemoteConfigPort
            // internally and only appears when the server sets a non-null payload.
            WhatsNewScreen(
                // The dialog has no extra work on dismissal — the caller owns the result.
                onDismiss = noopClick,
                modifier = Modifier,
            )
        },
    )
}

@Composable
private fun AppContent(
    deeplinkViewId: String?,
    deeplinkTaskId: String?,
    themeMode: ThemeMode,
    accent: SingularityAccents,
    fontSizeScale: Float,
) {
    val state = rememberNav3State()
    val navigator = remember(state) { Navigator(state) }
    val navCallbacks = remember(navigator) {
        NavCallbacks(
            navigate = navigator::open,
            goBack = navigator::goBack,
            close = navigator::close,
        )
    }

    LaunchedEffect(deeplinkViewId, deeplinkTaskId, navigator) {
        when {
            deeplinkTaskId != null -> {
                // Deep-link boundary: the raw String from the intent becomes a TaskId here,
                // so every route past this point is typed (REQ-NAV-005).
                navigator.open(
                    AppDestination.TasksGraph(
                        AppDestination.TasksStartRoute.Detail(TaskId(deeplinkTaskId)),
                    ),
                )
            }

            deeplinkViewId != null -> {
                navigator.open(AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaEdit(deeplinkViewId)))
            }
        }
    }

    SingularityTheme(themeMode = themeMode, accent = accent, fontSizeScale = fontSizeScale) {
        CompositionLocalProvider(
            LocalAppNavigator provides navCallbacks,
            // One provider for the whole tree, so shared components can pulse haptics without
            // resolving the port — and without a preview guard. Default is NoOpHaptic, which is
            // also what desktop binds, so this line changes nothing there.
            LocalHaptic provides koinInject(),
        ) {
            AuthGuard(koinInject()) {
                PlatformShell(state, navigator, navCallbacks)
            }
        }
    }
}

/**
 * Android actual implementation of [PlatformShell].
 */
@Composable
actual fun PlatformShell(state: Nav3State, navigator: Navigator, navCallbacks: NavCallbacks) {
    // From the graph, not the host (#91) — see the desktop shell for the full story.
    val shellClock: kotlin.time.Clock = koinInject()
    val shellZone: com.singularity.todo.core.platform.TimeZoneProvider = koinInject()
    androidShellNav3Root(
        state = state,
        navigator = navigator,
        navCallbacks = navCallbacks,
        today = com.singularity.todo.core.platform.todayAt(shellClock, shellZone.current()),
    )
}
