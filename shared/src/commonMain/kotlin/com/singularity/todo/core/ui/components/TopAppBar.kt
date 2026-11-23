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
import androidx.compose.runtime.remember
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
    // One token per composed bar: the holder uses it to tell the title of the
    // screen on screen from the title of a screen that is leaving.
    val barPublisher = remember { Any() }
    // Does this bar draw a leading control of its own? The Desktop shell draws a
    // back control of its own, and it stands down while a screen owns one: a
    // screen that draws a back arrow already owns the gesture, and a second
    // arrow on top of it would be two controls for one meaning.
    val backControlHolder = LocalScreenBackControlHolder.current
    val backControlPublisher = remember { Any() }
    if (backControlHolder != null) {
        val ownsControl = navigationIcon != null
        SideEffect { backControlHolder.publish(backControlPublisher, ownsControl) }
        DisposableEffect(backControlHolder, backControlPublisher) {
            onDispose { backControlHolder.release(backControlPublisher) }
        }
    }
    if (holder != null) {
        SideEffect {
            holder?.publishActions(barPublisher, actions)
            holder?.selectionTitle = selectionTitle
            holder?.onExitSelection = onExitSelection
            if (overrideShell) {
                holder?.publishTitle(barPublisher, title, navigationIcon)
            }
        }
        DisposableEffect(holder, overrideShell) {
            onDispose {
                // Only this bar's own row and selection state go away: a screen
                // underneath is still composed and must keep the row it published.
                holder?.releaseActions(barPublisher)
                holder?.selectionTitle = null
                holder?.onExitSelection = null
                // Only the bar that published the override takes it back down.
                // A screen that has BOTH this bar and a [ShellOverride] must
                // not have the second one's override cleared by the first one's
                // disposal.
                if (overrideShell) {
                    holder?.releaseTitle(barPublisher)
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
