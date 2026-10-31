package com.singularity.todo.core.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
        SideEffect {
            holder?.actions = actions
            holder?.selectionTitle = selectionTitle
            holder?.onExitSelection = onExitSelection
            if (overrideShell) {
                holder?.title = title
                holder?.navigationIcon = navigationIcon
                holder?.override = true
            }
        }
        DisposableEffect(holder, overrideShell) {
            onDispose {
                holder?.actions = null
                holder?.selectionTitle = null
                holder?.onExitSelection = null
                // Only the bar that published the override takes it back down.
                // A screen that has BOTH this bar and a [ShellOverride] must
                // not have the second one's override cleared by the first one's
                // disposal.
                if (overrideShell) {
                    holder?.title = null
                    holder?.navigationIcon = null
                    holder?.override = false
                }
            }
        }
        return
    }
    val resolvedColors = colors ?: TopAppBarDefaults.topAppBarColors(
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
