package com.singularity.todo.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android implementation: nothing is drawn.
 *
 * A phone's list is scrolled by touch, and the gesture is the position: the
 * platform draws its own scrollbar, fading it in while the finger moves and
 * out again afterwards. A bar that stays on screen would be a desktop
 * affordance on a device with no pointer, and would sit over the list's own
 * content for as long as the screen is open.
 *
 * It publishes neither [PlatformScrollbarSemantics] key, so a test that reads one
 * back is by construction a desktop test.
 *
 * This file is in `:shared`'s own `androidMain`, next to the `expect` in
 * `commonMain`. An `actual` has to live in the module that declares its
 * `expect`; putting it in a dependency does not satisfy the compiler.
 */
@Composable
actual fun Modifier.platformVerticalScrollbar(state: LazyListState): Modifier = this

/**
 * Android implementation: nothing is drawn, for the same reason as the lazy
 * list overload.
 */
@Composable
actual fun Modifier.platformVerticalScrollbar(state: ScrollState): Modifier = this
