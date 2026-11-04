package com.singularity.todo.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The desktop's vertical scrollbar, as a modifier.
 *
 * ## Why this is a seam
 *
 * A mouse-driven window has to say how long a list is and how far into it the
 * user has scrolled; a touch screen says it with the gesture itself, and the
 * bar the platform draws there fades in while the finger moves and out again
 * after. Compose keeps the persistent bar on the desktop artifact only, so
 * common code reaches it through here: on the desktop this draws the bar, on
 * Android it is the identity.
 *
 * ## What it does not decide
 *
 * The caller owns the scroll state and the list; this only paints the bar onto
 * it. A list that scrolls a [LazyListState] and a column that scrolls a
 * [ScrollState] are two different Compose types with two different adapters,
 * which is why there are two overloads rather than one.
 *
 * @param state the state of the scrolling container this modifier is attached
 *   to. The thumb's size and position are read from it, so the bar tracks the
 *   visible portion and dragging the thumb scrolls the container.
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
