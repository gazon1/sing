package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The space the shell's floating action button takes above the bottom edge of
 * the shell's content.
 *
 * Material3's `Scaffold` does not reserve room for a floating action button:
 * the content is laid out behind it, and the last row of a list ends up under
 * the button. The shell owns the button (ADR
 * `docs/decisions/2026-09-07-fab-chrome-level.md`), so the shell measures it
 * and publishes the space it takes here, and a list adds that space to its own
 * bottom content padding.
 *
 * Zero when no button is on screen: a list must not keep blank space for a
 * button that is not there.
 */
val LocalFabBottomSpace = compositionLocalOf<Dp> { 0.dp }

/**
 * The measured footprint of the shell's floating action button.
 *
 * The height is measured rather than assumed — the shell draws a medium button
 * on desktop and an extended one on Android — and the gap below it is the one
 * Material3's `Scaffold` leaves between the button and the content (`FabSpacing`
 * there, and private).
 */
@Stable
class FabFootprint {
    private var heightPx by mutableIntStateOf(0)

    /** Records the button's measured height, in pixels. */
    fun onMeasured(heightPx: Int) {
        this.heightPx = heightPx
    }

    /**
     * The space the button takes above the bottom edge of the content.
     *
     * Zero until the button has been laid out: a button that has not been
     * measured is not on screen yet.
     */
    @Composable
    fun bottomSpace(): Dp {
        val density = LocalDensity.current
        return if (heightPx == 0) 0.dp else with(density) { heightPx.toDp() } + FabSpacing
    }

    private companion object {
        /**
         * The gap Material3's `Scaffold` leaves below a floating action button.
         *
         * Mirrors Material3's own `private val FabSpacing get() = 16.dp` in the
         * `Scaffold` source (material3 1.13.0-beta02, the Compose Multiplatform
         * mirror of `androidx.compose.material3`): the same file places the
         * button with `fabOffsetFromBottom = fabHeight + FabSpacing +
         * contentWindowInsets.bottom`, so this is the gap between the content's
         * bottom edge and the top of the button.
         */
        val FabSpacing = 16.dp
    }
}
