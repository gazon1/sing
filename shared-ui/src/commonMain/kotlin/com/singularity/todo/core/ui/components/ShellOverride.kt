package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Publishes [title] (and optionally [navigationIcon]) to the Android shell bar
 * when the shell owns the only bar, then renders nothing locally. On desktop
 * (no [LocalScreenActions]) this is a no-op and the caller renders its own bar.
 *
 * Centralises the publication otherwise duplicated by screens that own their
 * header (Calendar, Settings, ...).
 *
 * The actions are NOT touched: a screen that has a bar of its own publishes
 * them through [TopAppBar], and a bar that clears what another bar published
 * takes away the actions of the screen it is standing next to.
 *
 * The header is published under this composition's own identity, so the bar it
 * takes down when it leaves is its own and not whatever the next screen has
 * published in the meantime ([ScreenActionsHolder.retractBar]).
 */
@Composable
fun ShellOverride(
    title: @Composable () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
) {
    val holder = LocalScreenActions.current ?: return
    val owner = remember { Any() }
    SideEffect {
        holder.publishBar(owner = owner, title = title, navigationIcon = navigationIcon)
    }
    DisposableEffect(holder, owner) {
        onDispose { holder.retractBar(owner) }
    }
}
