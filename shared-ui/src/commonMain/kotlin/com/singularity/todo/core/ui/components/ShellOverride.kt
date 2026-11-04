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
 * Centralises the SideEffect/DisposableEffect ceremony for screens that own
 * their header (Calendar, Settings, ...) and for `TopAppBar(overrideShell =
 * true)`, which delegates its override here.
 *
 * The actions are NOT touched: a screen that has a bar of its own publishes
 * them through [TopAppBar], and the two publishers own separate slots.
 */
@Composable
fun ShellOverride(
    title: @Composable () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
) {
    val holder = LocalScreenActions.current ?: return
    // The token is stable for this composition, so this override takes down
    // only its own publication: another publisher's bar stays up.
    val owner = remember { Any() }
    SideEffect {
        holder.publishBar(owner, title, navigationIcon)
    }
    DisposableEffect(holder, owner) {
        onDispose { holder.withdrawBar(owner) }
    }
}
