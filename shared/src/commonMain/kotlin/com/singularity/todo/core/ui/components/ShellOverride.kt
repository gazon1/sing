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
 *
 * The actions are NOT touched: a screen that has a bar of its own publishes
 * them through [TopAppBar], and a bar that clears what another bar published
 * takes away the actions of the screen it is standing next to.
 */
@Composable
fun ShellOverride(
    title: @Composable () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
) {
    val holder = LocalScreenActions.current ?: return
    SideEffect {
        holder.title = title
        holder.navigationIcon = navigationIcon
        holder.override = true
    }
    DisposableEffect(holder) {
        onDispose {
            holder.title = null
            holder.navigationIcon = null
            holder.override = false
        }
    }
}
