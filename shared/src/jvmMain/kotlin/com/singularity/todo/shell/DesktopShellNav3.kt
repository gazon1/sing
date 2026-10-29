package com.singularity.todo.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.core.ui.menu.ComposeTopMenuBar
import com.singularity.todo.core.ui.menu.MenuNode
import com.singularity.todo.core.ui.menu.buildMenuNodes
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.LocalNav3State
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.topMostRoute
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.Settings
import com.singularity.todo.feature.nav.createJvmEntryProvider
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.nav.icon
import com.singularity.todo.feature.projects.presentation.nav.ProjectsOpener
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeIntent
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import java.net.URI
import kotlin.system.exitProcess

/**
 * Navigation 3 Desktop shell — the implementation called by [PlatformShell].
 *
 * Uses the same terrakok nav3-recipes multiplestacks pattern as Android:
 * - [Nav3State] and [Navigator] are owned by [App] and passed in as parameters
 * - [NavDisplay] renders all active stacks
 *
 * Desktop UI: [ModalNavigationDrawer] with hamburger menu (instead of bottom bar on Android).
 * No FAB on Desktop.
 *
 * Window menu bar is added at the top using [ComposeTopMenuBar].
 *
 * @param today the date the shell navigates and prefills with, supplied by the caller
 *   from the Koin graph (#91). Required: the calendar anchor and the FAB prefill are
 *   decided here, and reading the host's wall clock here made a test's fixed `Clock`
 *   reach every ViewModel and none of the shell.
 */
@Composable
fun DesktopShellNav3Root(
    state: Nav3State,
    navigator: Navigator,
    navCallbacks: NavCallbacks,
    today: kotlinx.datetime.LocalDate,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val current: AppDestination = state.topLevelRoute as? AppDestination
        ?: AppDestination.AgendaGraph(AgendaStartRoute.Today)

    // Topmost route in the current stack — determines the FAB and back arrow.
    // When a nested screen (e.g. SavedAgendaEdit) is on top, use it instead of the tab.
    val topMostRoute: NavKey = state.topMostRoute()
    val fabCurrent: AppDestination = topMostRoute as? AppDestination
        ?: current

    val currentStack = state.requireBackStackFor(state.topLevelRoute)
    val canGoBack = currentStack.size > 1

    val appEntryProvider = createJvmEntryProvider(navCallbacks, today)

    // The projects the drawer shows as a tree — the same tree the Android sheet
    // shows, drawn by the same composable.
    val projectsTreeViewModel: ProjectsTreeViewModel = koinViewModel()
    val projectsTreeState by projectsTreeViewModel.stateFlow.collectAsStateWithLifecycle()
    val projectsOpener: ProjectsOpener = koinInject()

    // About dialog state
    var showAbout by remember { mutableStateOf(false) }

    // Window menu bar entries
    val menuEntries = remember(navigator) {
        val viewItems = DestinationKind.tabs.map { dest ->
            MenuNode.Action(
                id = "view_${
                    dest.title.replace(" ", "_")
                        .lowercase()
                }",
                label = dest.title,
                onClick = { navigator.open(dest) },
            )
        }
        buildMenuNodes {
            subMenu(
                "file",
                "File",
                children = buildMenuNodes {
                    item("new_task", "New Task", shortcut = "Ctrl+N") {
                        navigator.open(AppDestination.TasksGraph(start = AppDestination.TasksStartRoute.Create()))
                    }
                    item("settings", "Settings…", shortcut = "Ctrl+,") {
                        navigator.open(AppDestination.Settings)
                    }
                    divider()
                    item("quit", "Quit", shortcut = "Ctrl+Q") {
                        exitProcess(0)
                    }
                },
            )
            subMenu(
                "edit",
                "Edit",
                children = buildMenuNodes {
                    item("find", "Find", shortcut = "Ctrl+F") {
                        navigator.open(AppDestination.Search)
                    }
                },
            )
            subMenu(
                "agent",
                "Agent",
                children = buildMenuNodes {
                    item("external_agent", "External Agent…") {
                        navigator.open(AppDestination.ExternalAgent)
                    }
                },
            )
            subMenu("view", "View", children = viewItems)
            subMenu(
                "help",
                "Help",
                children = buildMenuNodes {
                    item("about", "About Singularity Todo") {
                        showAbout = true
                    }
                    item("github", "Open GitHub…") {
                        openGitHub()
                    }
                },
            )
        }
    }

    // #445 blank-screen diagnostic — TEMPORARY: to be replaced with proper CI alerting.
    // Wraps NavDisplay so the composable call-chain includes toDecoratedEntries (a @Composable).
    // The LaunchedEffect inside fires on topLevelRoute changes and warns if the active route
    // has no entries — which would render a silently blank NavDisplay with no error.
    val navDisplayEntries = state.toDecoratedEntries(appEntryProvider)
    LaunchedEffect(state.topLevelRoute) {
        if (navDisplayEntries.isEmpty()) {
            println(
                "WARNING [blank-screen] topLevelRoute=${state.topLevelRoute} has 0 entries. " +
                    "Check that the entryProvider covers this route.",
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TestTags.DESKTOP_DRAWER),
            ) {
                // Five tabs + a divider + eight menu entries is a fixed column that is
                // taller than a 1024x768 window, so without a scroll the trailing entries —
                // Settings last of all — are clipped and unreachable. The drawer looked
                // complete while one destination simply could not be opened, and a click on
                // the clipped node resolves in the semantics tree but lands outside the
                // window, so the navigation it names silently never happens. Same defect and
                // same fix as the settings nav rail (see SettingsNavRail).
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Navigation",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )

                    DestinationKind.tabs.forEach { destination ->
                        val selected = current == destination
                        NavigationDrawerItem(
                            label = { Text(destination.title) },
                            selected = selected,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigator.open(destination)
                            },
                            icon = {
                                Icon(
                                    destination.icon,
                                    contentDescription = destination.title,
                                )
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // The same entries the Android sheet offers — one model,
                    // two drawings.
                    AppMenu.destinations.forEach { entry ->
                        val selected = current == entry.destination
                        NavigationDrawerItem(
                            label = { Text(entry.label) },
                            selected = selected,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigator.open(entry.destination)
                            },
                            icon = {
                                Icon(
                                    entry.icon ?: Icons.Default.Menu,
                                    contentDescription = entry.label,
                                )
                            },
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .testTag(entry.id),
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // ─── Projects ───────────────────────────────────────────────
                    // The tree, and the way to the full list. The section is
                    // drawn by the composable the Android sheet draws too.
                    ProjectsMenuSection(
                        state = projectsTreeState,
                        onToggleExpanded = { projectId ->
                            projectsTreeViewModel.onIntent(ProjectsTreeIntent.ToggleExpanded(projectId))
                        },
                        onOpenProject = { projectId ->
                            scope.launch { drawerState.close() }
                            // The request is a piece of app state, not a
                            // destination: the project id stays out of the
                            // navigation key space.
                            projectsOpener.requestOpen(projectId)
                            navigator.open(AppDestination.ProjectsGraph())
                        },
                        onOpenAllProjects = {
                            scope.launch { drawerState.close() }
                            // The hoisted stack survives the graph leaving the
                            // screen, so the list is asked for explicitly.
                            projectsOpener.requestList()
                            navigator.open(AppDestination.ProjectsGraph())
                        },
                        headerModifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        treeModifier = Modifier.padding(horizontal = 12.dp),
                        itemModifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
        content = {
            Scaffold(
                modifier = Modifier.onPreviewKeyEvent { event ->
                    // shortcut: hotkey dispatch has no automated test yet; covered by manual smoke.
                    handleDesktopShortcut(event, navigator)
                },
                floatingActionButton = {
                    // One `today` for the shell, as on Android (#91): the prefill and
                    // the tab it was tapped from must not disagree across a midnight.
                    val action = fabActionForNav3(fabCurrent, today) { navigator.open(it) }
                    if (action != null) {
                        FloatingActionButton(onClick = action.onClick) {
                            Icon(Icons.Default.Add, contentDescription = action.label)
                        }
                    }
                },
                floatingActionButtonPosition = FabPosition.End,
                topBar = {
                    Column {
                        ComposeTopMenuBar(entries = menuEntries)
                        TopAppBar(
                            title = { Text(current.title) },
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            if (canGoBack) {
                                                navigator.goBack()
                                            } else if (drawerState.isClosed) {
                                                drawerState.open()
                                            } else {
                                                drawerState.close()
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag(TestTags.SHELL_BACK_BUTTON),
                                ) {
                                    Icon(
                                        imageVector = if (canGoBack) {
                                            Icons.AutoMirrored.Filled.ArrowBack
                                        } else {
                                            Icons.Default.Menu
                                        },
                                        contentDescription = if (canGoBack) "Back" else "Menu",
                                    )
                                }
                            },
                        )
                    }
                },
            ) { padding ->
                CompositionLocalProvider(LocalNav3State provides state) {
                    NavDisplay(
                        entries = navDisplayEntries,
                        onBack = { navigator.goBack() },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        },
    )

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Singularity Todo") },
        text = {
            Text(
                "Version ${appVersion().name}\n\nA KMP task manager for Android and Desktop.\nBuilt with Kotlin Multiplatform.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}

private fun openGitHub() {
    try {
        java.awt.Desktop.getDesktop()
            .browse(URI("https://github.com/singularity-todo"))
    } catch (_: Throwable) {
        // Desktop browsing not supported on this platform (including HeadlessException
        // which is an Error in headless CI environments with no display)
    }
}

internal fun handleDesktopShortcut(event: KeyEvent, navigator: Navigator): Boolean {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
    return when (event.key) {
        Key.N -> {
            navigator.open(AppDestination.TasksGraph(start = AppDestination.TasksStartRoute.Create()))
            true
        }

        Key.Comma -> {
            navigator.open(AppDestination.Settings)
            true
        }

        Key.Q -> {
            exitProcess(0)
            true
        }

        Key.F -> {
            navigator.open(AppDestination.Search)
            true
        }

        else -> false
    }
}
