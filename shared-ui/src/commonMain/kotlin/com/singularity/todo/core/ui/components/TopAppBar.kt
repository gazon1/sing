package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

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
        SideEffect {
            holder.actions = actions
            holder.selectionTitle = selectionTitle
            holder.onExitSelection = onExitSelection
        }
        // The publication below happens once, when this composition enters.
        // Its content is read through these, so the bar still shows what the
        // screen says now without the screen publishing again.
        val publishedTitle by rememberUpdatedState(title)
        val publishedIcon by rememberUpdatedState(navigationIcon)
        val icon: (@Composable () -> Unit)? =
            if (navigationIcon == null) null else { publishedIcon?.invoke() }
        DisposableEffect(holder, overrideShell, barOwner) {
            // Published here and retracted below — deliberately NOT from the
            // SideEffect above, which runs on every recomposition. A screen on
            // its way out still recomposes while the navigation transition
            // plays; republishing let it take the bar back from the screen that
            // had just published, and its own retraction then erased that
            // screen's header. One publication per composition is what makes
            // [ScreenActionsHolder.retractBar]'s owner check sound.
            if (overrideShell) {
                holder.publishBar(owner = barOwner, title = { publishedTitle() }, navigationIcon = icon)
            }
            onDispose {
                holder.actions = null
                holder.selectionTitle = null
                holder.onExitSelection = null
                // Only the bar that published the override takes it back down,
                // and only while it is still the bar the shell shows: a screen
                // that has BOTH this bar and a [ShellOverride] must not have
                // the second one's override cleared by the first one's
                // disposal, and a screen leaving during a navigation must not
                // erase the header the arriving screen has already published.
                if (overrideShell) {
                    holder.retractBar(barOwner)
                }
            }
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
