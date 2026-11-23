package com.singularity.todo.core.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Who owns the back control on the screen that is on top.
 *
 * A screen that draws a leading control of its own — a back arrow — owns the back
 * gesture for as long as it is composed. The Desktop shell reads this to keep its
 * own back control from stacking a second arrow on top of that screen: one back
 * control, drawn by whoever is on top.
 *
 * The holder keeps one entry per composed bar, in publication order, and the last
 * entry wins — the screen that published last is the one on top. A screen that
 * publishes "no control of my own" takes the ownership back: it is the screen the
 * user is looking at, and its answer is the one that counts.
 *
 * Android does not need this: its shell owns the only bar there, and a screen that
 * owns a back arrow publishes that arrow to the shell itself (see
 * [ScreenActionsHolder]).
 */
class ScreenBackControlHolder {
    private class Entry(val publisher: Any, val ownsControl: Boolean)

    private val entries = mutableListOf<Entry>()

    /** Whether the screen on top draws a back control of its own. */
    var ownsControl: Boolean by mutableStateOf(false)
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
        ownsControl = entries.lastOrNull()?.ownsControl == true
    }
}

/**
 * The holder the Desktop shell provides to the screens it draws.
 *
 * `null` everywhere else: without a shell that draws a back control of its own,
 * nobody has to know who owns the gesture.
 */
val LocalScreenBackControlHolder = compositionLocalOf<ScreenBackControlHolder?> { null }
