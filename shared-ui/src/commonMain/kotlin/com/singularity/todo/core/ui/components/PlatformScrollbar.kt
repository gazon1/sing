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
 *
 * ## The state must be the one the container scrolls
 *
 * This takes the state rather than creating it precisely so that a caller can
 * pass a state it *already* gave the container. Pass the same instance to both
 * (`LazyColumn(state = listState, …)` and `.platformVerticalScrollbar(listState)`)
 * and nothing in this seam can go wrong; pass a second `rememberLazyListState()`
 * and the bar is drawn from a state no list ever scrolls — full length, and
 * stuck. The code cannot enforce the identity of the instance, so every call
 * site is a pair: the list's `state =` and this modifier, same variable. All of
 * them hold today, including the two that keep the state as a parameter instead
 * of a local (the agenda's list and the saved-agenda list take
 * `listState: LazyListState` from their screen and thread it to both).
 *
 * ## Where the two `actual`s live, and why
 *
 * In `:shared`, under `src/androidMain` and `src/jvmMain` — not in this module.
 * That is this seam family's standing convention, not an oversight: `:shared-ui`
 * has no `androidMain` or `jvmMain` source set at all, and every other platform
 * seam declared here pairs the same way, with its actuals next to the platform
 * API it calls — `mapTestTagsAsResourceIds` (enforced by
 * `UiAutomationSelectorTest`), `PlatformBackHandler` and `onSecondaryClick` all
 * declare their `expect` in this file's module and their `actual` in
 * `:shared`'s platform source sets. `:shared-ui` carries an `api` dependency on
 * `:shared`, and the resulting pair is what every screen in this module
 * compiles and runs against. Moving these two files into `:shared-ui` would
 * break that convention and prove nothing about whether they work.
 *
 * ## Why no test asserts on the bar
 *
 * A scrollbar is a draw-and-hit-test decoration: Compose's `verticalScrollbar`
 * contributes no semantics node, so `runDesktopAppTest` and
 * `runIsolatedComposeTest` cannot see it, and a flow over a long seeded list
 * would pass with the bar attached to the wrong state exactly as happily as
 * with the right one. The observable contract is therefore the source-level one
 * above — the modifier and the list share one state variable at every call site
 * — plus the manual check the change was written against: on desktop, a list
 * longer than the window draws a thumb that tracks the visible portion and
 * drags.
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
