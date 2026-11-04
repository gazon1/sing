package com.singularity.todo.core.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior

/**
 * Platform-aware TopAppBar: no-op on Android (shell owns the only bar),
 * delegates to Material3 on desktop.
 *
 * `overrideShell` is the answer to a screen that draws its own header on a
 * platform where the shell owns the only bar. The screen publishes its title
 * (and, optionally, its navigation icon) to the shell and the shell draws them
 * instead of its own; without it the screen would either draw a second bar or
 * lose its title entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit) = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    selectionTitle: String? = null,
    onExitSelection: (() -> Unit)? = null,
    overrideShell: Boolean = false,
) {
    val holder = LocalScreenActions.current
    if (holder != null) {
        val barOwner = remember { Any() }
        // The route this bar belongs to, captured as the composition enters.
        // A bar that is leaving still recomposes while the transition plays, and
        // `remember` without keys keeps the route it entered with: the slots it
        // publishes late stay tagged with the screen they were made for, and the
        // shell — which is already asking for the arriving route's slots — does
        // not draw them (#1962 review).
        val route = LocalScreenActionsRoute.current
        val entryRoute = remember { route }
        // The publication below happens once, when this composition enters.
        // Its content is read through these, so the bar still shows what the
        // screen says now without the screen publishing again.
        val publishedTitle by rememberUpdatedState(title)
        val publishedIcon by rememberUpdatedState(navigationIcon)
        DisposableEffect(holder, overrideShell, barOwner) {
            // The slots are claimed here and not from the side effect below,
            // which runs on every recomposition: a screen on its way out still
            // recomposes while the navigation transition plays, and republishing
            // let it take the bar back from the screen that had just published.
            // One claim (and at most one header publication) per composition is
            // what makes the owner checks in [ScreenActionsHolder] sound.
            holder.claimActions(owner = barOwner, route = entryRoute)
            if (overrideShell) {
                holder.publishBar(
                    owner = barOwner,
                    title = { publishedTitle() },
                    // Asked when the shell draws the bar, not now: a screen can
                    // start or stop supplying a navigation icon while it stays
                    // on screen (#1962 review).
                    navigationIcon = { publishedIcon },
                )
            }
            onDispose {
                // Only the bar that claimed the slots empties them: a bar
                // leaving during a transition must not take the arriving
                // screen's actions with it (#1962 review).
                holder.clearActions(barOwner)
                // Only the bar that published the override takes it back down,
                // and only while it is still the header the shell shows: a
                // screen that has BOTH this bar and a [ShellOverride] must not
                // have the second one's override cleared by the first one's
                // disposal, and a screen leaving during a navigation must not
                // erase the header the arriving screen has already published.
                if (overrideShell) {
                    holder.retractBar(barOwner)
                }
            }
        }
        // The side effect comes after the claim on purpose: the two run in the
        // same frame, and a publication that arrives before the claim it
        // belongs to is dropped — the bar's buttons would then stay empty until
        // something else made the bar recompose.
        SideEffect {
            holder.publishSlots(
                owner = barOwner,
                actions = actions,
                selectionTitle = selectionTitle,
                onExitSelection = onExitSelection,
            )
        }
        return
    }
    val resolvedColors =
        colors ?: TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        )
    androidx.compose.material3.TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon ?: {},
        actions = actions,
        windowInsets = windowInsets,
        colors = resolvedColors,
        scrollBehavior = scrollBehavior,
        expandedHeight = expandedHeight,
    )
}
