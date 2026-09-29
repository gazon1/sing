package com.singularity.todo.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.core.ui.menu.ComposeTopMenuBar
import com.singularity.todo.core.ui.menu.MenuNode
import com.singularity.todo.core.ui.menu.buildMenuNodes
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.Settings
import com.singularity.todo.feature.nav.createJvmEntryProvider
import com.singularity.todo.feature.nav.icon
import kotlinx.coroutines.launch
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
 */
@Composable
fun DesktopShellNav3Root(state: Nav3State, navigator: Navigator, navCallbacks: NavCallbacks) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val current: AppDestination = state.topLevelRoute as? AppDestination
        ?: AppDestination.AgendaGraph(AgendaStartRoute.Today)

    val appEntryProvider = createJvmEntryProvider(navCallbacks)

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
                onClick = { navigator.navigate(dest) },
            )
        }
        buildMenuNodes {
            subMenu(
                "file",
                "File",
                children = buildMenuNodes {
                    item("new_task", "New Task", shortcut = "Ctrl+N") {
                        navigator.navigate(AppDestination.TasksGraph(start = AppDestination.TasksStartRoute.Create))
                    }
                    item("settings", "Settings…", shortcut = "Ctrl+,") {
                        navigator.navigate(AppDestination.Settings)
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
                    item("undo", "Undo", enabled = false, shortcut = "Ctrl+Z") {}
                    item("redo", "Redo", enabled = false, shortcut = "Ctrl+Y") {}
                    divider()
                    item("find", "Find", shortcut = "Ctrl+F") {
                        navigator.navigate(AppDestination.Search)
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                                navigator.navigate(destination)
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

                    DestinationKind.menuEntries.forEach { destination ->
                        val selected = current == destination
                        NavigationDrawerItem(
                            label = { Text(destination.title) },
                            selected = selected,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigator.navigate(destination)
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
                }
            }
        },
        content = {
            Scaffold(
                floatingActionButton = {
                    val action = fabActionForNav3(current) { navigator.navigate(it) }
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
                                IconButton(onClick = {
                                    scope.launch {
                                        if (drawerState.isClosed) {
                                            drawerState.open()
                                        } else {
                                            drawerState.close()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
                            },
                        )
                    }
                },
            ) { padding ->
                NavDisplay(
                    entries = state.toDecoratedEntries(appEntryProvider),
                    onBack = { navigator.goBack() },
                    modifier = Modifier.padding(padding),
                )
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
    } catch (_: Exception) {
        // Desktop browsing not supported on this platform
    }
}
