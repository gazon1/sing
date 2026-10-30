package com.singularity.todo.core.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.singularity.todo.core.ui.isAndroidPlatform

/**
 * Platform-aware TopAppBar: no-op on Android (shell owns the only bar),
 * delegates to Material3 on desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit) = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    forceShow: Boolean = false,
) {
    if (isAndroidPlatform && !forceShow) {
        val holder = LocalScreenActions.current
        SideEffect { holder?.actions = actions }
        DisposableEffect(holder) { onDispose { holder?.actions = null } }
        return
    }
    val resolvedColors = colors ?: TopAppBarDefaults.topAppBarColors()
    androidx.compose.material3.TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = windowInsets,
        colors = resolvedColors,
        scrollBehavior = scrollBehavior,
        expandedHeight = expandedHeight,
    )
}
