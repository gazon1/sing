package com.singularity.todo.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey

/**
 * The desktop's vertical scrollbar, as a modifier.
 *
 * On the desktop this draws a bar that says how long a scrolling container is and
 * how far into it the user has scrolled; the platform draws its own bar while the
 * finger moves, so on Android this is the identity. Compose keeps the persistent
 * bar on the desktop artifact only, which is why common code reaches it through
 * this seam. The module layout, the alternatives weighed and the testing strategy
 * are recorded in
 * `docs/decisions/2026-11-04-the-scrollbar-publishes-the-state-it-draws-from.md`.
 *
 * ## The state must be the state the container scrolls
 *
 * The modifier takes the state rather than creating one so that a caller can hand
 * it the state it already gave the container: `LazyColumn(state = listState, …)`
 * and `.platformVerticalScrollbar(listState)`, one variable. A second
 * `rememberLazyListState()` would draw the bar from a state nothing scrolls — full
 * length, and stuck — and the type system cannot forbid it, so every call site is
 * a pair. Most create the variable locally; the agenda's list is handed its state
 * by the screen and passes the same instance on.
 *
 * [PlatformScrollbarSemantics] is what makes that pair checkable instead of merely
 * documented: the desktop actual publishes the instance it draws from on the
 * container, so a test can scroll the container and read back the state the bar is
 * using.
 *
 * ## Where the modifier goes
 *
 * On the same node as the container's `state =`. A width cap sits next to it and
 * draws the same bar on either side, because the bar takes its geometry from the
 * node it wraps and a `widthIn` reports its child's width: the notes and projects
 * lists write `…platformVerticalScrollbar(state).widthIn(max = 640.dp)` and the bar
 * sits at the capped column's edge on a wide window. The one way to move the bar
 * off its column is to pin the column to the window — a `fillMaxSize()` outside the
 * cap, which the agenda's list has — and then the column and the bar are both the
 * window's width.
 *
 * The scroll states a desktop bar draws from, published on the container it is
 * attached to.
 *
 * A scrollbar draws and hit-tests but publishes no semantics of its own, so without
 * this a test could not tell a container that carries the bar from one that lost
 * it. The published value is the *instance* the bar was handed, not a marker: a
 * second `rememberLazyListState()` at a call site publishes a state the container
 * never scrolls, and a test that scrolls the container and then reads the published
 * state sees the difference. A marker could not show that.
 *
 * Public on purpose. The assertions live in `:desktopApp`'s `jvmTest`, which sees
 * this module's public API only; `internal` would leave the contract untestable.
 * A [SemanticsPropertyKey] is namespaced, so unlike a shared `testTag` string it
 * cannot collide with a container's own tag, and two lists on one screen publish
 * two keys on two nodes rather than one ambiguous value.
 *
 * Android publishes neither key: the actual there is the identity.
 */
object PlatformScrollbarSemantics {
    /** The [LazyListState] the bar on this container is drawn from. */
    val listState: SemanticsPropertyKey<LazyListState> =
        SemanticsPropertyKey("PlatformScrollbarListState")

    /** The [ScrollState] the bar on this column is drawn from. */
    val columnState: SemanticsPropertyKey<ScrollState> =
        SemanticsPropertyKey("PlatformScrollbarColumnState")
}

/**
 * The vertical scrollbar for a [LazyListState], as a modifier.
 *
 * @param state the state of the scrolling container this modifier is attached to.
 *   The thumb's size and position are read from it, so the bar tracks the visible
 *   portion and dragging the thumb scrolls the container.
 */
@Composable
expect fun Modifier.platformVerticalScrollbar(state: LazyListState): Modifier

/**
 * The vertical scrollbar for a [androidx.compose.foundation.layout.Column]
 * scrolled with `Modifier.verticalScroll` — the same bar as the lazy-list
 * overload, over the other kind of scroll state.
 *
 * @param state the state of the column this modifier is attached to.
 */
@Composable
expect fun Modifier.platformVerticalScrollbar(state: ScrollState): Modifier
