package com.singularity.todo

import android.app.Activity
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LocalScreenActions
import com.singularity.todo.core.ui.components.ScreenActionsHolder
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.LocalNav3State
import com.singularity.todo.feature.nav.MENU_BUTTON_TITLE
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.createAppEntryProvider
import com.singularity.todo.feature.nav.icon
import com.singularity.todo.feature.nav.topMostRoute
import com.singularity.todo.feature.projects.presentation.nav.ProjectsOpener
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeIntent
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeViewModel
import com.singularity.todo.shell.MenuBottomSheet
import com.singularity.todo.shell.ShellSearchButton
import com.singularity.todo.shell.fabActionForNav3
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

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

    val showAttachAccount =
        com.singularity.todo.shell
            .rememberIsAnonymousSession()

    // topLevelRoute is MutableState<NavKey>, getValue triggers recomposition on change
    val current: AppDestination =
        state.topLevelRoute as? AppDestination
            ?: AppDestination.AgendaGraph(AgendaStartRoute.Today)

    // Topmost route in the current stack — determines the FAB, mirroring desktop.
    val topMostRoute: NavKey = state.topMostRoute()
    val fabCurrent: AppDestination = topMostRoute as? AppDestination ?: current

    val fabAction = fabActionForNav3(fabCurrent, today) { navigator.open(it) }

    val screenActions = remember { ScreenActionsHolder() }

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
                        contentDescription = MENU_BUTTON_TITLE,
                    )
                },
                label = { Text(MENU_BUTTON_TITLE) },
                modifier = Modifier.testTag(TestTags.NAV_MENU_BUTTON),
            )
        },
    ) {
        Scaffold(
            topBar = {
                ShellTopBar(
                    screenActions = screenActions,
                    destinationTitle = current.title,
                    isSearch = current == AppDestination.Search,
                    navigator = navigator,
                    onMenu = { menuVisible = true },
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

                // The blank-tree diagnostic (REQ-DN-004) is recorded by
                // `toDecoratedEntries` itself, before its unresolvable-entry fallback.
                // Drop what the screen we are leaving told the bar, BEFORE the entries
                // below compose (#2008, #1962).
                //
                // The order is the whole fix. Cleared from a LaunchedEffect — which runs
                // once the frame is applied, after every screen's publication — the clear
                // erased the header the arriving screen had just published, and the agenda
                // lost its title and greeting on a tab switch. Done here, in composition
                // and ahead of the entries, the arriving screen publishes last: a screen
                // that owns its bar has it drawn, and a screen that publishes nothing
                // leaves the shell's own destination title in place instead of inheriting
                // the previous screen's header.
                //
                // This is also why the clear cannot live in a DisposableEffect on the
                // route: effect order is not the contract, composition order is.
                val route = state.topLevelRoute
                val lastRoute = remember { mutableStateOf(route) }
                if (lastRoute.value != route) {
                    lastRoute.value = route
                    screenActions.onTopLevelRouteChanged()
                }
                val navDisplayEntries = state.toDecoratedEntries(appEntryProvider)

                CompositionLocalProvider(LocalNav3State provides state, LocalScreenActions provides screenActions) {
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

/**
 * The bar the shell draws at the top of the Android app.
 *
 * It is a function of its own because the shell root composes a navigation
 * suite, a scaffold, a FAB and a menu sheet, and the bar's own decisions —
 * selection mode, a screen that owns its bar, back or menu — would count
 * against the root's complexity. The bar is also the part of the shell that
 * changes most often, and a bar buried in the middle of a scaffold is a bar
 * nobody dares to change.
 */
@Composable
private fun ShellTopBar(
    screenActions: ScreenActionsHolder,
    destinationTitle: String,
    isSearch: Boolean,
    navigator: Navigator,
    onMenu: () -> Unit,
) {
    TopAppBar(
        title = { ShellBarTitle(screenActions = screenActions, destinationTitle = destinationTitle) },
        navigationIcon = {
            ShellNavigationIcon(screenActions = screenActions, navigator = navigator, onMenu = onMenu)
        },
        actions = {
            screenActions.actions?.invoke(this)
            if (screenActions.selectionTitle == null && !isSearch) {
                ShellSearchButton(navigator = navigator)
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    )
}

/**
 * The title of the shell bar.
 *
 * A screen that owns its own bar publishes it here, and the shell draws it
 * instead of the destination's name: the month the calendar is on says more
 * than the word "Calendar".
 */
@Composable
private fun ShellBarTitle(
    screenActions: ScreenActionsHolder,
    destinationTitle: String,
) {
    val selection = screenActions.selectionTitle
    val publishedTitle = screenActions.title
    when {
        selection != null -> Text(selection)
        screenActions.override && publishedTitle != null -> publishedTitle.invoke()
        else -> Text(destinationTitle)
    }
}

/**
 * The navigation icon of the shell bar.
 *
 * The published icon replaces the shell's own only when the screen published
 * one. A screen that published a title and no icon leaves the shell's back
 * button alone: the shell's button knows where the shell is.
 */
@Composable
private fun ShellNavigationIcon(
    screenActions: ScreenActionsHolder,
    navigator: Navigator,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    val exitSelection = screenActions.onExitSelection
    val publishedIcon = if (screenActions.override) screenActions.navigationIcon else null
    when {
        exitSelection != null -> {
            IconButton(
                onClick = exitSelection,
                modifier = Modifier.testTag(TestTags.SHELL_BACK_BUTTON),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Exit selection",
                )
            }
        }

        publishedIcon != null -> {
            publishedIcon.invoke()
        }

        else -> {
            // Toggle between Back (nested screen or previous tab) and Menu
            // (ultimate root tab), mirroring Desktop.
            if (navigator.canGoBack()) {
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
            } else {
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier.testTag(TestTags.NAV_MENU_BUTTON),
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = MENU_BUTTON_TITLE,
                    )
                }
            }
        }
    }
}
