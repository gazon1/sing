package com.singularity.todo.feature.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import kotlinx.coroutines.launch

/**
 * Desktop drawer shell — used by [com.singularity.todo.shell.DesktopShell].
 *
 * Two flavours:
 * - [DrawerStyle.Modal] — hamburger-triggered drawer (mobile-style).
 * - [DrawerStyle.Permanent] — always-visible side rail (desktop-style).
 *
 * Stateless: receives the [NavDestination] selection and an `onSelect`
 * callback. The caller (`DesktopShell`) decides what each drawer entry
 * navigates to (it maps `NavDestination` → `AppDestination` and calls
 * `navigator::navigateTopLevel`).
 *
 * After the [AppDestination] refactor, this drawer no longer needs
 * `NavGroup` — destinations are shown as a flat list. Re-introduce
 * grouping later if the list grows past ~8 entries.
 */
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
        drawerContent = { DesktopSidebar(current, closeAndSelect, modifier = Modifier) },
    ) {
        Scaffold { padding ->
            content(Modifier.padding(padding))
        }
    }
}

@Composable
private fun PermanentShell(
    current: NavDestination,
    onSelect: (NavDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        DesktopSidebar(
            current = current,
            onSelect = onSelect,
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .testTag(TestTags.DESKTOP_SIDEBAR),
        )
        VerticalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            content(Modifier)
        }
    }
}

@Composable
private fun DesktopSidebar(
    current: NavDestination,
    onSelect: (NavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(vertical = 12.dp),
    ) {
        Text(
            "Singularity Todo",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(4.dp))
        NavDestination.entries.forEach { dest ->
            NavigationDrawerItem(
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(dest.title) },
                selected = dest == current,
                onClick = { onSelect(dest) },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}
