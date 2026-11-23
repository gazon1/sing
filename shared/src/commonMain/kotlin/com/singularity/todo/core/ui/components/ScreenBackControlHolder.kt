package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Who owns the back control on the screen that is on top.
 *
 * A screen that draws a leading control of its own — a back arrow — owns the back
 * gesture for as long as it is composed. The Desktop shell reads this to keep its
 * own back control from stacking a second arrow on top of that screen: one back
 * control, drawn by whoever is on top.
 *
 * The holder keeps one entry per composed bar, in the order the bars were
 * composed, and the last entry wins — the screen that came up last is the one on
 * top. A screen that publishes "no control of my own" takes the ownership back:
 * it is the screen the user is looking at, and its answer is the one that counts.
 *
 * The owner is published as the *identity* of the bar that owns the control, not as
 * a flag. That is what keeps the two controls apart: the shell draws its control
 * while [owner] is null, and a screen draws its own while it is the owner. Both
 * read the same fact, so they cannot both be on screen — not even for the one frame
 * in which the screen on top is still publishing.
 *
 * Android does not need this: its shell owns the only bar there, and a screen that
 * owns a back arrow publishes that arrow to the shell itself (see
 * [ScreenActionsHolder]).
 */
class ScreenBackControlHolder {
    private class Entry(val publisher: Any, val ownsControl: Boolean)

    private val entries = mutableListOf<Entry>()

    /** The bar that owns the back control, or null while no bar does. */
    var owner: Any? by mutableStateOf<Any?>(null)
        private set

    /** Publishes the answer of the bar [publisher] belongs to. */
    fun publish(
        publisher: Any,
        ownsControl: Boolean,
    ) {
        val index = entries.indexOfFirst { it.publisher === publisher }
        if (index >= 0) {
            entries[index] = Entry(publisher, ownsControl)
        } else {
            entries.add(Entry(publisher, ownsControl))
        }
        publishOwner()
    }

    /** Takes down [publisher]'s answer, if it still has one. */
    fun release(publisher: Any) {
        if (entries.removeAll { it.publisher === publisher }) publishOwner()
    }

    private fun publishOwner() {
        owner = entries.lastOrNull()?.takeIf { it.ownsControl }?.publisher
    }
}

/**
 * The holder the Desktop shell provides to the screens it draws.
 *
 * `null` everywhere else: without a shell that draws a back control of its own,
 * nobody has to know who owns the gesture.
 */
val LocalScreenBackControlHolder = compositionLocalOf<ScreenBackControlHolder?> { null }

/**
 * Publishes that this screen draws a back control of its own, and answers whether
 * it may draw it right now.
 *
 * A screen that draws its leading control inside a [TopAppBar] gets this for free —
 * the bar publishes on the screen's behalf. A screen that draws one in a row of its
 * own — the external agent screen draws its arrow beside its title — publishes
 * through this, so the Desktop shell stands down while that screen is on top and
 * the two controls never appear together.
 *
 * Without a shell that draws a back control of its own there is nobody to compete
 * with, and the screen always draws its own control.
 */
@Composable
fun PublishScreenBackControl(ownsControl: Boolean = true): Boolean {
    val holder = LocalScreenBackControlHolder.current
    val publisher = remember { Any() }
    if (holder != null) {
        SideEffect { holder.publish(publisher, ownsControl) }
        DisposableEffect(holder, publisher) {
            onDispose { holder.release(publisher) }
        }
    }
    return holder == null || holder.owner === publisher
}
