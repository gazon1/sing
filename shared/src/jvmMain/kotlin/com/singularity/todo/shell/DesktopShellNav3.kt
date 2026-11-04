package com.singularity.todo.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.core.ui.DesktopShellLabels
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.menu.ComposeTopMenuBar
import com.singularity.todo.core.ui.menu.MenuNode
import com.singularity.todo.core.ui.menu.buildMenuNodes
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.LocalNav3State
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.Settings
import com.singularity.todo.feature.nav.createJvmEntryProvider
import com.singularity.todo.feature.nav.icon
import com.singularity.todo.feature.nav.topMostRoute
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
 * @param onCloseRequest what a back with nothing left to pop does — the Desktop half of
 *   the Android shell bar's `goBack()`/`activity.finish()` pair (#1523). Supplied by the
 *   host through [LocalWindowCloseRequest]; the no-op default keeps a shell mounted
 *   without a window (a test, a preview) from ending a process it does not own.
 */
@Composable
fun DesktopShellNav3Root(
    state: Nav3State,
    navigator: Navigator,
    navCallbacks: NavCallbacks,
    today: kotlinx.datetime.LocalDate,
    onCloseRequest: () -> Unit = {},
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val current: AppDestination =
        state.topLevelRoute as? AppDestination
            ?: AppDestination.AgendaGraph(AgendaStartRoute.Today)

    // Topmost route in the current stack — determines the FAB and back arrow.
    // When a nested screen (e.g. SavedAgendaEdit) is on top, use it instead of the tab.
    val topMostRoute: NavKey = state.topMostRoute()
    val fabCurrent: AppDestination =
        topMostRoute as? AppDestination
            ?: current

    // A back that has nothing left to pop leaves the app, as it does on Android, where the
    // same `goBack()` result finishes the activity. The window is the host's, so the shell
    // asks for the close instead of ending the process itself. Both back paths — the one
    // `NavDisplay` invokes and the one the screens' own back arrows call — are this value,
    // so a single press cannot pop and close at once.
    val shellCallbacks =
        remember(navCallbacks, navigator, onCloseRequest) {
            navCallbacks.copy(goBack = { desktopBack(navigator, onCloseRequest) })
        }

    val appEntryProvider = createJvmEntryProvider(shellCallbacks, today)

    // The projects the drawer shows as a tree — the same tree the Android sheet
    // shows, drawn by the same composable.
    val projectsTreeViewModel: ProjectsTreeViewModel = koinViewModel()
    val projectsTreeState by projectsTreeViewModel.stateFlow.collectAsStateWithLifecycle()
    val projectsOpener: ProjectsOpener = koinInject()

    val showAttachAccount = rememberIsAnonymousSession()

    // About dialog state
    var showAbout by remember { mutableStateOf(false) }

    // Window menu bar entries
    val menuEntries =
        remember(navigator) {
            val viewItems =
                DestinationKind.tabs.map { dest ->
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
                    children =
                        buildMenuNodes {
                            item("new_task", "New Task", shortcut = "Ctrl+N") {
                                navigator.open(
                                    AppDestination.TasksGraph(start = AppDestination.TasksStartRoute.Create()),
                                )
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
                    children =
                        buildMenuNodes {
                            item("find", "Find", shortcut = "Ctrl+F") {
                                navigator.open(AppDestination.Search)
                            }
                        },
                )
                subMenu(
                    "agent",
                    "Agent",
                    children =
                        buildMenuNodes {
                            item("external_agent", "External Agent…") {
                                navigator.open(AppDestination.ExternalAgent)
                            }
                        },
                )
                subMenu("view", "View", children = viewItems)
                subMenu(
                    "help",
                    "Help",
                    children =
                        buildMenuNodes {
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

    // The blank-tree diagnostic (REQ-DN-004) is recorded inside `toDecoratedEntries`,
    // before its unresolvable-entry fallback: that is the one place that still sees the
    // empty stacks, and it runs whether or not the tree has anything to draw into.
    val navDisplayEntries = state.toDecoratedEntries(appEntryProvider)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier =
                    Modifier
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
                            modifier =
                                Modifier
                                    .padding(horizontal = 12.dp)
                                    .testTag(entry.id),
                        )
                    }

                    if (showAttachAccount) {
                        val entry = AppMenu.attachAccount
                        NavigationDrawerItem(
                            label = { Text(entry.label) },
                            selected = current == entry.destination,
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
                            modifier =
                                Modifier
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
                modifier =
                    Modifier.onPreviewKeyEvent { event ->
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
                    ComposeTopMenuBar(
                        entries = menuEntries,
                        leading = {
                            DrawerTrigger(
                                onClick = {
                                    scope.launch {
                                        if (drawerState.isClosed) {
                                            drawerState.open()
                                        } else {
                                            drawerState.close()
                                        }
                                    }
                                },
                            )
                        },
                    )
                },
            ) { padding ->
                CompositionLocalProvider(LocalNav3State provides state) {
                    NavDisplay(
                        entries = navDisplayEntries,
                        onBack = shellCallbacks.goBack,
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

/**
 * The desktop shell's drawer trigger.
 *
 * The window's application menu lists the same destinations, but its entries can only
 * open the screen they name — this is the one control that opens the drawer holding
 * them, and the drawer had no way in at all after #1562 dropped the bar it sat in.
 *
 * It stays a drawer trigger on every screen rather than turning into a back arrow: the
 * navigation policy treats a drawer entry as a switch of top-level destination, so a
 * "can go back" reading is true on every tab but the first, and a control that became a
 * back arrow there would take the drawer away again. Back is each screen's own arrow,
 * and the window's back request.
 *
 * Its content description is [DesktopShellLabels.DRAWER_TRIGGER], and it is read from
 * there rather than written here: that constant is part of the desktop flow suite's
 * contract — the helpers bind `DesktopShell.HAMBURGER` to the same value — and a name
 * only one of the two sides reads is a name the other side silently stops matching.
 */
@Composable
private fun DrawerTrigger(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Menu, contentDescription = DesktopShellLabels.DRAWER_TRIGGER)
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
        java.awt.Desktop
            .getDesktop()
            .browse(URI("https://github.com/singularity-todo"))
    } catch (_: Throwable) {
        // Desktop browsing not supported on this platform (including HeadlessException
        // which is an Error in headless CI environments with no display)
    }
}

/**
 * The Desktop shell's one back policy: pop, or — when [Navigator.goBack] reports there is
 * nothing left to pop — ask the host to close the window.
 *
 * The Android shell bar pairs the same result with `activity.finish()`
 * (`ShellNavigationIcon`); this function is the Desktop half of that parity, and it is
 * the only place the pairing is written, so the `NavDisplay` back and every screen's back
 * arrow cannot drift apart.
 *
 * @param navigator the app's single navigation facade.
 * @param onCloseRequest what the host does with a close — the window's own close path.
 */
internal fun desktopBack(
    navigator: Navigator,
    onCloseRequest: () -> Unit,
) {
    if (!navigator.goBack()) onCloseRequest()
}

internal fun handleDesktopShortcut(
    event: KeyEvent,
    navigator: Navigator,
): Boolean {
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

        else -> {
            false
        }
    }
}
