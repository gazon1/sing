package com.singularity.todo.shell

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavigator
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.MenuButtonTitle
import com.singularity.todo.feature.nav.icon
import com.singularity.todo.core.ui.TestTags

/**
 * Android app chrome: Scaffold + bottom navigation bar + per-tab FAB +
 * NavHost + an on-demand `MenuBottomSheet` overlay.
 *
 * Architecture:
 * - [Scaffold] owns layout (bottom bar, FAB, content padding).
 * - `AppNavHost` renders inside `content` and reacts to bottom-bar taps.
 * - `MenuBottomSheet` is **not** a navigation destination — it's a
 *   local overlay (`rememberSaveable`) that appears above the NavHost
 *   without disturbing the underlying back stack.
 *
 * **Per-tab FAB**: the FAB action depends on the current top-level
 * destination. Today/Inbox → `TaskEditor`, Plans → `ProjectEditor`,
 * Habits → no FAB (Pomodoro has its own controls). Other tabs hide the
 * FAB entirely.
 *
 * Edge-to-edge: `contentWindowInsets` keeps horizontal insets so we don't
 * overlap the status bar / gesture nav. The bottom bar applies
 * [WindowInsets.navigationBars] padding so it sits *above* the gesture
 * inset (see reference plan §17).
 *
 * @param content Optional override for the central content slot.
 *   Defaults to `AppNavHost`. Tests use this to substitute a synthetic
 *   graph and keep the chrome test independent of every feature's wiring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidShell(
    navigator: AppNavigator,
    content: @Composable (Modifier) -> Unit = { modifier ->
        AppNavHost(navigator = navigator, modifier = modifier)
    },
) {
    val current = navigator.currentTopLevelDestination()
    var menuVisible by rememberSaveable { mutableStateOf(false) }

    val fabAction = fabActionFor(current, navigator)

    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
                ),
            ) {
                DestinationKind.tabs.forEach { destination ->
                    val selected = current == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigator.navigateTopLevel(destination) },
                        icon = {
                            Icon(
                                destination.icon,
                                contentDescription = destination.title,
                                modifier = Modifier.testTag(TestTags.navTab(destination.title.lowercase()))
                            )
                        },
                        label = { Text(destination.title) },
                    )
                }
                NavigationBarItem(
                    selected = false,
                    onClick = { menuVisible = true },
                    icon = {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = MenuButtonTitle,
                            modifier = Modifier.testTag(TestTags.NAV_MENU_BUTTON)
                        )
                    },
                    label = { Text(MenuButtonTitle) },
                )
            }
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
        content(Modifier.padding(padding))
    }

    if (menuVisible) {
        MenuBottomSheet(
            onDismiss = { menuVisible = false },
            onSelect = { dest ->
                menuVisible = false
                navigator.navigateTopLevel(dest)
            },
        )
    }
}

/** Action + label for the FAB on a given top-level destination. */
private data class FabAction(val label: String, val onClick: () -> Unit)

/** Pick the right FAB action for the current destination. Returns null to hide it. */
private fun fabActionFor(current: AppDestination, navigator: AppNavigator): FabAction? = when (current) {
    AppDestination.Inbox, AppDestination.Today -> FabAction("Add task") {
        navigator.navigate(AppDestination.TaskEditor())
    }
    AppDestination.Plans -> FabAction("Add project") {
        navigator.navigate(AppDestination.ProjectEditor())
    }
    AppDestination.Habits, AppDestination.Calendar -> null
    else -> null
}
