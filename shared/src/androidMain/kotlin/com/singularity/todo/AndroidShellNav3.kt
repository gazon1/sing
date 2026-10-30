package com.singularity.todo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
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
 * Adaptive: [NavigationSuiteScaffold] switches between bottom bar and nav rail based
 * on window size, so no manual adaptive logic is needed.
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
@Suppress("LongMethod")
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

    val showAttachAccount = com.singularity.todo.shell.rememberIsAnonymousSession()

    // topLevelRoute is MutableState<NavKey>, getValue triggers recomposition on change
    val current: AppDestination = state.topLevelRoute as? AppDestination
        ?: AppDestination.AgendaGraph(AgendaStartRoute.Today)

    // Topmost route in the current stack — determines the FAB, mirroring desktop.
    val topMostRoute: NavKey = state.topMostRoute()
    val fabCurrent: AppDestination = topMostRoute as? AppDestination ?: current

    val fabAction = fabActionForNav3(fabCurrent, today) { navigator.open(it) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            DestinationKind.tabs.forEach { destination ->
                val selected = current == destination
                item(
                    selected = selected,
                    onClick = { navigator.open(destination) },
                    icon = {
                        Icon(
                            destination.icon,
                            contentDescription = destination.title,
                        )
                    },
                    label = { Text(destination.title) },
                    modifier = Modifier.testTag(TestTags.navTab(destination.title)),
                )
            }
            item(
                selected = false,
                onClick = { menuVisible = true },
                icon = {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = MenuButtonTitle,
                    )
                },
                label = { Text(MenuButtonTitle) },
                modifier = Modifier.testTag(TestTags.NAV_MENU_BUTTON),
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(current.title) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!navigator.goBack()) {
                                    (context as? Activity)?.finish()
                                }
                            },
                            modifier = Modifier.testTag(TestTags.SHELL_BACK_BUTTON),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
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
            Box(Modifier.fillMaxSize().padding(padding)) {
                val appEntryProvider = createAppEntryProvider(navCallbacks, today)

                // #445 blank-screen diagnostic — TEMPORARY: to be replaced with proper CI alerting.
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
                            if (!navigator.goBack()) {
                                (context as? Activity)?.finish()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (menuVisible) {
            MenuBottomSheet(
                onDismiss = { menuVisible = false },
                currentDestination = current,
                onSelect = { dest ->
                    menuVisible = false
                    if (dest == AppDestination.ProjectsGraph()) {
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
                    projectsOpener.requestOpen(projectId)
                    navigator.open(AppDestination.ProjectsGraph())
                },
                showAttachAccount = showAttachAccount,
            )
        }
    }
}
