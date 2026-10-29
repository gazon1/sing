package com.singularity.todo

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.app.Activity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.LocalNav3State
import com.singularity.todo.feature.nav.MenuButtonTitle
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.topMostRoute
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.createAppEntryProvider
import com.singularity.todo.feature.nav.icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.projects.presentation.nav.ProjectsOpener
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeIntent
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeViewModel
import com.singularity.todo.shell.MenuBottomSheet
import com.singularity.todo.shell.fabActionForNav3

/**
 * Navigation 3 Android shell — the actual implementation called by [PlatformShell].
 *
 * Uses the terrakok nav3-recipes multiplestacks pattern:
 * - [rememberNavBackStack] per top-level route (Android SavedState)
 * - [rememberSaveableStateHolderNavEntryDecorator] preserves each stack's state across tab swaps
 * - [Navigator] class handles navigation events (navigate + goBack)
 * - [NavDisplay] renders all active stacks
 *
 * [Nav3State] and [Navigator] are owned by [App] and passed in as parameters.
 * The [rememberNav3State] factory lives in commonMain as an expect/actual pair
 * (see [Nav3StateFactory.kt]).
 *
 * @param today the date the shell navigates and prefills with, supplied by the caller
 *   from the Koin graph (#91). Required for the same reason as the desktop shell: a
 *   date the shell computes for itself is a date a test's fixed `Clock` cannot reach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun androidShellNav3Root(
    state: Nav3State,
    navigator: Navigator,
    navCallbacks: NavCallbacks,
    today: kotlinx.datetime.LocalDate,
) {
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // The projects the menu shows as a tree. The ViewModel lives in the shell,
    // not in the sheet: the sheet is a separate window, and the tree has to be
    // the same tree whichever way the menu is opened.
    val projectsTreeViewModel: ProjectsTreeViewModel = koinViewModel()
    val projectsTreeState by projectsTreeViewModel.stateFlow.collectAsStateWithLifecycle()
    val projectsOpener: ProjectsOpener = koinInject()

    // topLevelRoute is MutableState<NavKey>, getValue triggers recomposition on change
    val current: AppDestination = state.topLevelRoute as? AppDestination
        ?: AppDestination.AgendaGraph(AgendaStartRoute.Today)

    // Topmost route in the current stack — determines the FAB, mirroring desktop.
    val topMostRoute: NavKey = state.topMostRoute()
    val fabCurrent: AppDestination = topMostRoute as? AppDestination ?: current

    val fabAction = fabActionForNav3(fabCurrent, today) { navigator.open(it) }

    Scaffold(
        bottomBar = {
            AndroidBottomBar(
                current = current,
                onSelectTab = { destination -> navigator.open(destination) },
                onOpenMenu = { menuVisible = true },
            )
        },
        floatingActionButton = {
            val action = fabAction ?: return@Scaffold
            ExtendedFloatingActionButton(
                onClick = action.onClick,
                icon = { Icon(Icons.Default.Add, contentDescription = action.label) },
                text = { Text(action.label) },
                modifier = Modifier.testTag(TestTags.TASKS_FAB),
            )
        },
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        // The same `today` the FAB uses, so the calendar anchor and the task
        // prefill agree (#91).
        val appEntryProvider = createAppEntryProvider(navCallbacks, today)

        // #445 blank-screen diagnostic — TEMPORARY: to be replaced with proper CI alerting.
        // toDecoratedEntries is composable so compute it once above, then check in LaunchedEffect.
        val navDisplayEntries = state.toDecoratedEntries(appEntryProvider)
        LaunchedEffect(state.topLevelRoute) {
            if (navDisplayEntries.isEmpty()) {
                println(
                    "WARNING [blank-screen] topLevelRoute=${state.topLevelRoute} has 0 entries. " +
                        "Check that the entryProvider covers this route.",
                )
            }
        }

        CompositionLocalProvider(LocalNav3State provides state) {
            NavDisplay(
                entries = navDisplayEntries,
                onBack = {
                    val stack = state.requireBackStackFor(state.topLevelRoute)
                    if (stack.size > 1) navigator.goBack() else (context as? Activity)?.finish()
                },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (menuVisible) {
        MenuBottomSheet(
            onDismiss = { menuVisible = false },
            currentDestination = current,
            onSelect = { dest ->
                menuVisible = false
                if (dest == AppDestination.ProjectsGraph()) {
                    // The list is asked for explicitly: the graph's stack is
                    // hoisted, so it would otherwise keep the last project.
                    projectsOpener.requestList()
                }
                navigator.open(dest)
            },
            treeState = projectsTreeState,
            onToggleExpanded = { projectId ->
                projectsTreeViewModel.onIntent(ProjectsTreeIntent.ToggleExpanded(projectId))
            },
            onOpenProject = { projectId ->
                menuVisible = false
                // The request is a piece of app state, not a destination: the
                // project id stays out of the navigation key space.
                projectsOpener.requestOpen(projectId)
                navigator.open(AppDestination.ProjectsGraph())
            },
        )
    }
}

/**
 * The bottom bar of the Android shell: the tabs and the menu button.
 *
 * The menu is a bar item rather than an overflow icon in a top bar because the
 * app menu is a first-class surface — it holds the project tree, the calendar
 * and the destinations — and a bar item gives it a place a thumb can reach.
 *
 * @param current the destination the shell is on.
 * @param onSelectTab called with the tab the user tapped.
 * @param onOpenMenu called when the user taps the menu button.
 */
@Composable
private fun AndroidBottomBar(
    current: AppDestination,
    onSelectTab: (AppDestination) -> Unit,
    onOpenMenu: () -> Unit,
) {
    NavigationBar(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
        ),
    ) {
        DestinationKind.tabs.forEach { destination ->
            val selected = current == destination
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(destination) },
                icon = {
                    Icon(
                        destination.icon,
                        contentDescription = destination.title,
                    )
                },
                label = { Text(destination.title) },
                // On the item, not the icon: a tag on a decorative child is merged
                // into the clickable node and never surfaces as a resource-id.
                modifier = Modifier.testTag(TestTags.navTab(destination.title)),
            )
        }
        NavigationBarItem(
            selected = false,
            onClick = onOpenMenu,
            icon = {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = MenuButtonTitle,
                )
            },
            label = { Text(MenuButtonTitle) },
            modifier = Modifier.testTag(TestTags.NAV_MENU_BUTTON),
        )
    }
}
