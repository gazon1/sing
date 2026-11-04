package com.singularity.todo.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.singularity.todo.feature.projects.presentation.theme.ProjectColorPalette
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [contrastingForeground] is what stands between a colour the user picked and a tick nobody
 * can see, and the swatches that use it paint colours from the project palette — whose entries
 * sit across the whole luminance range, not only at its two ends.
 *
 * The tests below therefore pin the crossover where the rule puts it, mid-band colours
 * included: a test on black and white alone stays green if the rule stops reading luminance
 * altogether, and every mid-band user-chosen colour then regresses unseen.
 */
@Tag("fast")
class ContrastingForegroundTest {
    @Test
    fun `an almost white colour gets a black foreground`() {
        assertEquals(Color.Black, Color.White.contrastingForeground())
    }

    @Test
    fun `an almost black colour gets a white foreground`() {
        assertEquals(Color.White, Color(0xFF1A237E).contrastingForeground())
    }

    @Test
    fun `a colour above the crossover gets a black foreground`() {
        // Palette Yellow: light enough to be uncomfortable under white, and the brightest
        // entry a user can actually choose.
        val yellow = paletteColor("Yellow")
        assertTrue(yellow.luminance() > 0.5f, "Yellow must stay above the crossover: ${yellow.luminance()}")

        assertEquals(Color.Black, yellow.contrastingForeground())
    }

    @Test
    fun `a mid-luminance colour still follows the crossover, not the extremes`() {
        // Palette Green and Orange are neither light nor dark — the band the extreme-only
        // tests never reached, and the band every converted call site can land in.
        listOf("Green", "Orange").forEach { name ->
            val colour = paletteColor(name)
            assertTrue(
                colour.luminance() in 0.2f..0.5f,
                "$name must stay in the mid band for this test to mean anything: ${colour.luminance()}",
            )
            assertEquals(Color.White, colour.contrastingForeground(), "$name sits below the crossover")
        }
    }

    @Test
    fun `every palette colour gets a foreground, never the colour itself`() {
        ProjectColorPalette.all.forEach { argb ->
            val colour = Color(argb)
            assertTrue(
                colour.contrastingForeground() == Color.Black || colour.contrastingForeground() == Color.White,
                "palette colour ${ProjectColorPalette.nameOf(argb)} got a foreground that is neither black nor white",
            )
        }
    }

    private fun paletteColor(name: String): Color {
        val argb =
            ProjectColorPalette.all.firstOrNull { ProjectColorPalette.nameOf(it) == name }
                ?: error("the project palette no longer has a colour named $name")
        return Color(argb)
    }
}
