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
 * site is a pair: the list's `state =` and this modifier, same variable. The
 * section below says how that pair is kept honest.
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
 * variable. To make at least the first half of that contract testable, the
 * desktop `actual` publishes [PLATFORM_SCROLLBAR_TEST_TAG] on the container it
 * is attached to — a scrollbar draws and hit-tests but publishes no semantics
 * of its own, so without the tag a test could not tell a list that carries the
 * bar from one that lost it. `PlatformScrollbarTest` asserts the tag on a long
 * list and its absence on a list without the modifier, so a screen that drops
 * the bar fails rather than silently losing an affordance.
 *
 * What the tag cannot show is whether the state handed to the bar is the one
 * the container scrolls — a second `rememberLazyListState()` would carry the
 * tag just as well. That is why every call site is written as a pair: one
 * variable, passed to the container's `state =` and to this modifier. Most call
 * sites create that variable locally; the agenda's list is handed
 * `listState: LazyListState` by its screen and passes the same instance on.
 */

/**
 * The tag the desktop bar publishes on the container it is attached to.
 *
 * See the file comment above: the bar itself publishes no semantics, so this is
 * what a test can address to prove a scrolling container still carries it.
 */
const val PLATFORM_SCROLLBAR_TEST_TAG: String = "platform-scrollbar"

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
