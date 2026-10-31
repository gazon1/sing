package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect

/**
 * Publishes [title] (and optionally [navigationIcon]) to the Android shell bar
 * when the shell owns the only bar, then renders nothing locally. On desktop
 * (no [LocalScreenActions]) this is a no-op and the caller renders its own bar.
 *
 * Centralises the SideEffect/DisposableEffect ceremony otherwise duplicated by
 * screens that own their header (Calendar, Settings, ...).
 */
@Composable
fun ShellOverride(
    title: @Composable () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val holder = LocalScreenActions.current ?: return
    SideEffect {
        holder.title = title
        holder.navigationIcon = navigationIcon
        holder.actions = actions
        holder.override = true
    }
    DisposableEffect(holder) {
        onDispose {
            holder.title = null
            holder.navigationIcon = null
            holder.actions = null
            holder.override = false
        }
    }
}
