package com.singularity.todo

import kotlin.time.Clock
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.platform.TimeZoneProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.singularity.todo.core.appearance.AppearanceSettingsRepository
import com.singularity.todo.core.auth.AuthGuard
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.LocalHaptic
import com.singularity.todo.core.ui.preview.noopClick
import com.singularity.todo.core.ui.text.LocalInternalLinkHandler
import com.singularity.todo.core.ui.text.TextLink
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.gate.presentation.screen.AppVersionGateScreen
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.deeplink.DeepLinkTarget
import com.singularity.todo.feature.nav.internalLinkDestination
import com.singularity.todo.feature.nav.rememberNav3State
import com.singularity.todo.feature.whatsnew.presentation.screen.WhatsNewScreen
import com.singularity.todo.shell.DesktopShellNav3Root
import org.koin.compose.koinInject
import java.awt.Desktop
import java.net.URI

private const val RELEASES_URL = "https://github.com/singularity-todo/singularity/releases"

/**
 * Opens the releases page in the system browser, or silently ignores any error
 * (e.g. [java.awt.HeadlessException] in headless CI environments).
 *
 * Exposed as a public function so the headless-CI path can be exercised by
 * [com.singularity.todo.feature.gate.AppVersionGateScreenTest][AppVersionGateScreenTest].
 */
public fun openStoreOnDesktop() {
    try {
        Desktop.getDesktop().browse(URI(RELEASES_URL))
    } catch (_: Throwable) {
        // Desktop browsing not available in headless CI environments
    }
}

/**
 * JVM Desktop actual implementation of [App].
 *
 * Renders [AppVersionGateScreen] first — the gate checks [RemoteConfigPort]
 * and shows a blocked UI if the running version is too old. The gate handles
 * its own "Check Again" retry. When the gate passes, it renders [AppContent].
 *
 * @param deepLinkTarget Screen to open on startup, or `null` for the start tab.
 */
@Composable
actual fun App(deepLinkTarget: DeepLinkTarget?) {
    val appearance: AppearanceSettingsRepository = koinInject()
    val themeMode by appearance.themeMode.collectAsState(initial = ThemeMode.System)
    val accentName by appearance.accentColor.collectAsState(initial = "blue")
    val accent = SingularityAccents.fromString(accentName)
    val fontSizeScale by appearance.fontSizeScale.collectAsState(initial = 1f)

    AppVersionGateScreen(
        playStoreUrl = RELEASES_URL,
        onOpenStore = { openStoreOnDesktop() },
        modifier = Modifier,
        content = {
            AppContent(
                deepLinkTarget = deepLinkTarget,
                themeMode = themeMode,
                accent = accent,
                fontSizeScale = fontSizeScale,
            )
            WhatsNewScreen(
                // The dialog has no extra work on dismissal — persistence is handled
                // internally via WhatsNewPrefs. Mirrors the Android caller, which
                // passes the same shared no-op rather than an empty lambda literal.
                onDismiss = noopClick,
                modifier = Modifier,
            )
        },
    )
}

@Composable
actual fun AppContent(
    deepLinkTarget: DeepLinkTarget?,
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

    // The deep link sets the tab *and* the stack of that tab, so "back" returns to
    // the tab's own screen. `LaunchedEffect` keyed by the target also makes a repeated
    // launch with the same URI idempotent: the key does not change, the effect does
    // not re-run, the stack is not rebuilt twice.
    LaunchedEffect(deepLinkTarget, state) {
        if (deepLinkTarget != null) {
            state.openDeepLink(deepLinkTarget)
        }
    }

    // A tapped internal link opens the screen it names, in the app. The handler is
    // remembered so that the composition local's value does not change on every
    // recomposition — a link screen would otherwise be recomposed for nothing.
    val internalLinkHandler = remember(navCallbacks) {
        { link: TextLink.Internal ->
            val destination = internalLinkDestination(link.target)
            if (destination != null) navCallbacks.navigate(destination)
        }
    }

    SingularityTheme(themeMode = themeMode, accent = accent, fontSizeScale = fontSizeScale) {
        CompositionLocalProvider(
            LocalAppNavigator provides navCallbacks,
            LocalInternalLinkHandler provides internalLinkHandler,
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
 * JVM Desktop actual implementation of [PlatformShell].
 */
@Composable
actual fun PlatformShell(state: Nav3State, navigator: Navigator, navCallbacks: NavCallbacks) {
    // The shell's `today` is read from the graph, not from the host (#91). This is the
    // last reach-around: the calendar anchor and the FAB prefill were computed in
    // `remember { }` outside Koin, so a test that bound a fixed `Clock` reached every
    // ViewModel and none of the shell. Safe here and only here — the shell is not
    // previewed, and this function already resolves two ports with `koinInject`.
    val shellClock: Clock = koinInject()
    val shellZone: TimeZoneProvider = koinInject()
    DesktopShellNav3Root(
        state = state,
        navigator = navigator,
        navCallbacks = navCallbacks,
        today = todayAt(shellClock, shellZone.current()),
    )
}
