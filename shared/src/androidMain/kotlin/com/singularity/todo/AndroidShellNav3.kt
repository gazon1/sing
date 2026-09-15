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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.MenuButtonTitle
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.createAppEntryProvider
import com.singularity.todo.feature.nav.icon
import com.singularity.todo.shell.MenuBottomSheet
import com.singularity.todo.core.ui.TestTags

/**
 * Navigation 3 Android shell — the actual expect implementation for [androidShellNav3].
 *
 * Uses the terrakok nav3-recipes multiplestacks pattern:
 * - [rememberNavBackStack] per top-level route (Android SavedState)
 * - [rememberSaveableStateHolderNavEntryDecorator] preserves each stack's state across tab swaps
 * - [Navigator] class handles navigation events (navigate + goBack)
 * - [NavDisplay] renders all active stacks
 *
 * [Nav3State] and [Navigator] are shared in commonMain. Platform-specific is only
 * the [rememberNav3State] factory (Android has a no-arg `rememberNavBackStack`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun androidShellNav3() {
    val state = rememberNav3State()
    val navigator = remember(state) { Navigator(state) }
    var menuVisible by rememberSaveable { mutableStateOf(false) }

    // topLevelRoute is MutableState<NavKey>, getValue triggers recomposition on change
    val current: AppDestination = state.topLevelRoute as? AppDestination
        ?: AppDestination.Today

    val fabAction = fabActionForNav3(current, navigator)

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
                        onClick = { navigator.navigate(destination) },
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
        val navCallbacks = NavCallbacks(
            navigate = navigator::navigate,
            goBack = navigator::goBack,
        )
        val appEntryProvider = createAppEntryProvider(navCallbacks)
        NavDisplay(
            entries = state.toDecoratedEntries(appEntryProvider),
            onBack = { navigator.goBack() },
            modifier = Modifier.padding(padding),
        )
    }

    if (menuVisible) {
        MenuBottomSheet(
            onDismiss = { menuVisible = false },
            onSelect = { dest ->
                menuVisible = false
                navigator.navigate(dest)
            },
        )
    }
}

private data class FabAction(val label: String, val onClick: () -> Unit)

private fun fabActionForNav3(current: AppDestination, navigator: Navigator): FabAction? = when (current) {
    AppDestination.Inbox, AppDestination.Today -> FabAction("Add task") { }
    AppDestination.Plans -> FabAction("Add project") { }
    AppDestination.Notes -> FabAction("Add note") { navigator.navigate(AppDestination.Notes) }
    AppDestination.Pomodoro, AppDestination.Statistics, AppDestination.Archive -> null
    else -> null
}

// ─── Android: rememberNavBackStack with no SavedStateConfiguration ────────────────

/**
 * Creates the multi-back-stack [Nav3State] for the app.
 * On Android, [rememberNavBackStack] is called without SavedStateConfiguration
 * (the platform provides a no-arg overload).
 */
@Composable
private fun rememberNav3State(): Nav3State {
    val startRoute: NavKey = AppDestination.Today
    val topLevelRoutes: Set<NavKey> =
        DestinationKind.tabs.toSet() + DestinationKind.menuEntries.toSet()

    val topLevelRoute: MutableState<NavKey> = remember(startRoute) {
        mutableStateOf(startRoute)
    }

    val backStacks = topLevelRoutes.associateWith { key ->
        rememberNavBackStack(key)
    }

    return remember(startRoute, topLevelRoutes) {
        Nav3State(startRoute, topLevelRoute, backStacks)
    }
}
