package com.singularity.todo.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Desktop implementation: the real scrollbar, drawn over the list.
 *
 * The adapter is remembered against the state so a recomposition does not
 * restart a drag that is in progress.
 *
 * The state is also published on the container through
 * [PlatformScrollbarSemantics.listState]. That is a semantics property, not a
 * `testTag`: a tag written here would be a second tag on a node that already has
 * one — the notes list carries `TestTags.NOTES_LIST` — and Compose keeps one value
 * per key, so whichever tag came second would silently replace the other.
 *
 * This file is in `:shared`'s own `jvmMain`, next to the `expect` in
 * `commonMain`. An `actual` has to live in the module that declares its
 * `expect` — a module that only depends on `:shared` could not satisfy it,
 * because a dependency's `expect` is not the dependent's to answer.
 */
@Composable
actual fun Modifier.platformVerticalScrollbar(state: LazyListState): Modifier =
    this.semantics { this[PlatformScrollbarSemantics.listState] = state }

/**
 * Desktop implementation: the real scrollbar, over a `verticalScroll` column.
 *
 * The state is published through [PlatformScrollbarSemantics.columnState], for the
 * reason given on the list overload.
 */
@Composable
actual fun Modifier.platformVerticalScrollbar(state: ScrollState): Modifier =
    this.semantics { this[PlatformScrollbarSemantics.columnState] = state }

/**
 * The bar's colours, taken from the theme.
 *
 * Compose's own default is black at 12 % — right on a white surface and
 * invisible on a dark one. The thumb is the theme's `onSurface`, so it is as
 * readable in the dark theme as in the light one, and it darkens on hover the
 * way the default does. Only the colours are ours; the dimensions are the
 * default ones.
 */
@Composable
private fun platformScrollbarStyle(): ScrollbarStyle {
    val thumb = MaterialTheme.colorScheme.onSurface
    return remember(thumb) {
        ScrollbarStyle(
            minimalHeight = 16.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 300,
            unhoverColor = thumb.copy(alpha = 0.12f),
            hoverColor = thumb.copy(alpha = 0.5f),
        )
    }
}
