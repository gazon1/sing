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
 * In this module, under `src/androidMain` and `src/jvmMain`, beside the
 * `expect`s above. `:shared` and `:shared-ui` are separate Gradle projects
 * (`settings.gradle.kts`), so each is a separate module to the compiler; an
 * `actual` in `:shared` would leave these `expect`s unsatisfied in `:shared-ui`
 * and would itself be an `actual` with no `expect` in its own module.
 *
 * ## How the pairing is kept honest
 *
 * The identity of the instance is a source-level contract, not something the
 * type system can check: the modifier and the container must be handed the same
 * variable. Compose's `verticalScrollbar` draws a layout node and publishes no
 * semantics, so a flow test over a long list cannot yet tell a bar wired to the
 * right state from one wired to a second `rememberLazyListState()`. Proving
 * that at the RCA level — so a screen that drops the bar fails a test — is
 * tracked separately (the desktop-scrollbar proof issue); until it lands, every
 * call site is reviewed as a pair, and the two that hoist the state (the
 * agenda's list and the saved-agenda list) take `listState: LazyListState` as a
 * parameter and pass that one instance to both the list and the modifier.
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
