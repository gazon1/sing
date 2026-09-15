package com.singularity.todo.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.createJvmEntryProvider
import com.singularity.todo.feature.nav.icon
import kotlinx.coroutines.launch

/**
 * Navigation 3 Desktop shell — the implementation called by [PlatformShell].
 *
 * Uses the same terrakok nav3-recipes multiplestacks pattern as Android:
 * - [Nav3State] and [Navigator] are owned by [App] and passed in as parameters
 * - [NavDisplay] renders all active stacks
 *
 * Desktop UI: [ModalNavigationDrawer] with hamburger menu (instead of bottom bar on Android).
 * No FAB on Desktop.
 */
@Composable
fun DesktopShellNav3Root(
    state: Nav3State,
    navigator: Navigator,
    navCallbacks: NavCallbacks,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val current: AppDestination = state.topLevelRoute as? AppDestination
        ?: AppDestination.Today

    val appEntryProvider = createJvmEntryProvider(navCallbacks)

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
                topBar = {
                    TopAppBar(
                        title = { Text(current.title) },
                        navigationIcon = {
                            IconButton(onClick = {
                                scope.launch {
                                    if (drawerState.isClosed) drawerState.open()
                                    else drawerState.close()
                                }
                            }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                    )
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
}
