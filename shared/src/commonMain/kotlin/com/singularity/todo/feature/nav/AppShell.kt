package com.singularity.todo.feature.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class DrawerStyle {
    Modal,
    Permanent,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    current: NavDestination,
    drawerStyle: DrawerStyle,
    onSelect: (NavDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    when (drawerStyle) {
        DrawerStyle.Modal -> ModalShell(current, onSelect, content)
        DrawerStyle.Permanent -> PermanentShell(current, onSelect, content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalShell(
    current: NavDestination,
    onSelect: (NavDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeAndSelect: (NavDestination) -> Unit = { dest ->
        onSelect(dest)
        scope.launch { drawerState.close() }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { AppDrawerContent(current, closeAndSelect) },
    ) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = current.topBarTitle(),
                    onMenuClick = { scope.launch { drawerState.open() } },
                )
            },
        ) { padding ->
            content(Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermanentShell(
    current: NavDestination,
    onSelect: (NavDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    PermanentNavigationDrawer(
        drawerContent = { AppDrawerContent(current, onSelect) },
    ) {
        Scaffold(
            topBar = { AppTopBar(title = current.topBarTitle()) },
        ) { padding ->
            content(Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(title: String, onMenuClick: (() -> Unit)? = null) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onMenuClick != null) {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Open menu")
                }
            }
        },
    )
}

@Composable
private fun AppDrawerContent(current: NavDestination, onSelect: (NavDestination) -> Unit) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.verticalScroll(scrollState)) {
        DrawerHeader()
        NavGroup.entries.forEach { group ->
            AppDrawerSection(
                group = group,
                destinations = NavDestination.grouped.getValue(group),
                current = current,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun DrawerHeader() {
    Icon(
        Icons.Default.DragHandle,
        contentDescription = null,
        modifier = Modifier.padding(8.dp),
    )
    Text(
        "Singularity Todo",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun AppDrawerSection(
    group: NavGroup,
    destinations: List<NavDestination>,
    current: NavDestination,
    onSelect: (NavDestination) -> Unit,
) {
    Text(
        text = group.label(),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    destinations.forEach { dest ->
        DrawerDestinationItem(
            destination = dest,
            selected = dest == current,
            onSelect = { onSelect(dest) },
        )
    }
}

@Composable
private fun DrawerDestinationItem(
    destination: NavDestination,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(destination.title) },
        selected = selected,
        onClick = onSelect,
        icon = { Icon(destination.icon, contentDescription = null) },
    )
}
