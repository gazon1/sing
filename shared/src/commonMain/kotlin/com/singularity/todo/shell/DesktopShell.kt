package com.singularity.todo.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavigator
import com.singularity.todo.feature.nav.AppShell
import com.singularity.todo.feature.nav.DrawerStyle
import com.singularity.todo.feature.nav.FabAction
import com.singularity.todo.feature.nav.NavDestination

/**
 * Desktop (JVM) chrome — reuses the existing [AppShell] drawer layout.
 *
 * Desktop chrome is a 240 dp left rail (VSCode/JetBrains-style) implemented
 * in [AppShell.PermanentShell]. [ModalShell] exists for future use but is
 * not wired to any platform.
 *
 * The only new responsibility is bridging the [AppNavigator] (which drives
 * Android) into the drawer-driven world of [AppShell]:
 * - `current` is derived from the navigator (one source of truth).
 * - `onSelect` translates a [NavDestination] (drawer enum) into the
 *   equivalent [AppDestination] tab and calls `navigateTopLevel`.
 * - `fabAction` is derived from the current top-level destination (mirrors AndroidShell).
 */
@Composable
fun DesktopShell(navigator: AppNavigator) {
    val current = navigator.currentTopLevelDestination()
    AppShell(
        current = current.toNavDestination(),
        drawerStyle = DrawerStyle.Permanent,
        onSelect = { drawerDest ->
            tabDestinationFor(drawerDest)?.let(navigator::navigateTopLevel)
        },
        fabAction = fabActionForDesktop(current, navigator),
        content = { modifier ->
            AppNavHost(navigator = navigator, modifier = modifier)
        },
    )
}

/** Desktop equivalent of [AndroidShell.fabActionFor]. Returns FAB action per tab. */
private fun fabActionForDesktop(current: AppDestination, navigator: AppNavigator): FabAction? = when (current) {
    AppDestination.Inbox, AppDestination.Today -> FabAction("Add task") {
        navigator.navigate(AppDestination.TaskEditor())
    }
    AppDestination.Plans -> FabAction("Add project") {
        navigator.navigate(AppDestination.ProjectEditor())
    }
    AppDestination.Notes -> FabAction("Add note") {
        navigator.navigate(AppDestination.NoteEditor())
    }
    AppDestination.Habits, AppDestination.Calendar, AppDestination.Archive -> null
    else -> null
}

/** Map a navigation-graph destination to the drawer's enum entry. */
private fun AppDestination.toNavDestination(): NavDestination = when (this) {
    AppDestination.Inbox -> NavDestination.Tasks
    AppDestination.Today -> NavDestination.Tasks
    AppDestination.Plans -> NavDestination.Projects
    AppDestination.Habits -> NavDestination.Pomodoro
    AppDestination.Calendar -> NavDestination.Statistics
    AppDestination.Notes -> NavDestination.Notes
    AppDestination.AiChat -> NavDestination.Chat
    AppDestination.Search -> NavDestination.Search
    AppDestination.Archive -> NavDestination.Archive
    AppDestination.Settings -> NavDestination.Settings
    is AppDestination.TaskDetail -> NavDestination.Tasks
    is AppDestination.TaskEditor -> NavDestination.Tasks
    is AppDestination.NoteDetail -> NavDestination.Notes
    is AppDestination.NoteEditor -> NavDestination.Notes
    is AppDestination.ProjectEditor -> NavDestination.Projects
    is AppDestination.ProjectDetail -> NavDestination.Projects
}

/**
 * Reverse mapping: pick the [AppDestination] tab that should open when
 * the user taps a drawer entry. Returns `null` for entries that don't
 * map cleanly (currently none — the drawer covers every tab + menu).
 */
private fun tabDestinationFor(drawerDest: NavDestination): AppDestination? = when (drawerDest) {
    NavDestination.Tasks -> AppDestination.Today
    NavDestination.Notes -> AppDestination.Notes
    NavDestination.Projects -> AppDestination.Plans
    NavDestination.Pomodoro -> AppDestination.Habits
    NavDestination.Statistics -> AppDestination.Calendar
    NavDestination.Chat -> AppDestination.AiChat
    NavDestination.Search -> AppDestination.Search
    NavDestination.Archive -> AppDestination.Archive
    NavDestination.Settings -> AppDestination.Settings
}
