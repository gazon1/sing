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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.nav.MenuButtonTitle
import com.singularity.todo.feature.nav.Nav3State
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.Navigator
import com.singularity.todo.feature.nav.createAppEntryProvider
import com.singularity.todo.feature.nav.icon
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
 * [Nav3State] and [Navigator] are owned by [App] and passed in as parameters.
 * The [rememberNav3State] factory lives in commonMain as an expect/actual pair
 * (see [Nav3StateFactory.kt]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun androidShellNav3Root(state: Nav3State, navigator: Navigator, navCallbacks: NavCallbacks) {
    var menuVisible by rememberSaveable { mutableStateOf(false) }

    // topLevelRoute is MutableState<NavKey>, getValue triggers recomposition on change
    val current: AppDestination = state.topLevelRoute as? AppDestination
        ?: AppDestination.AgendaGraph(AgendaStartRoute.Today)

    val fabAction = fabActionForNav3(current) { navigator.navigate(it) }

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
                            )
                        },
                        label = { Text(destination.title) },
                        // On the item, not the icon: a tag on a decorative child is merged
                        // into the clickable node and never surfaces as a resource-id.
                        modifier = Modifier.testTag(TestTags.navTab(destination.title)),
                    )
                }
                NavigationBarItem(
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
            // A ModalBottomSheet renders into its own window, so the app-root
            // testTagsAsResourceId never reaches it and every menu item tag stays
            // invisible to UI automation. It has to be declared inside the sheet's
            // own content, and the property only exists on Android — which is why it
            // arrives as a parameter instead of living in this commonMain composable.
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        )
    }
}
