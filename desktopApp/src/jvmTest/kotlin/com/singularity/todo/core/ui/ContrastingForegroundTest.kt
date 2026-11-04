package com.singularity.todo.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.singularity.todo.feature.projects.presentation.theme.ProjectColorPalette
import org.junit.jupiter.api.Tag
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [contrastingForeground] is what stands between a colour the user picked and a tick nobody
 * can see, and the swatches that use it paint colours from the project palette — whose entries
 * sit across the whole luminance range, not only at its two ends.
 *
 * The tests therefore measure contrast rather than polarity: "white on a dark colour" is the
 * mistake the rule exists to prevent, and a test that only compares black against white stays
 * green while every mid-band colour regresses. An earlier version of this file asserted white
 * for Green and Orange, which certified that mistake — white reaches 2.8:1 and 2.2:1 there,
 * below WCAG AA and below the 3:1 large-text floor.
 *
 * It lives in `desktopApp`'s JVM suite rather than in `shared-ui/src/commonTest` because
 * `shared-ui` declares no test source set at all: no `commonTest`, no test dependency, and no
 * test task in the gate registry, so a test placed there would compile nowhere and run
 * nowhere. `:desktopApp:test` is the gate that runs tests over Compose code from `shared-ui` —
 * `MonthGridLongPressTest` in this same tree covers its month grid the same way.
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
    fun `a mid-luminance colour gets black, not the white a midpoint rule gives`() {
        // Green and Orange are the band an extreme-only test never reaches, and the band that
        // made a "luminance > 0.5" rule a no-op: both stay below 0.5, yet black is by far the
        // more legible ink — 7.6:1 and 9.7:1, against white's 2.8:1 and 2.2:1.
        listOf("Green", "Orange").forEach { name ->
            val colour = paletteColor(name)
            assertTrue(
                colour.luminance() in 0.2f..0.5f,
                "$name must stay in the mid band for this test to mean anything: ${colour.luminance()}",
            )
            assertTrue(
                contrastRatio(Color.White, colour) < 4.5,
                "white on $name should miss WCAG AA, or this test proves nothing: " +
                    "${contrastRatio(Color.White, colour)}",
            )
            assertEquals(Color.Black, colour.contrastingForeground(), "$name is more readable under black")
        }
    }

    @Test
    fun `the two palette entries nearest the crossover still clear WCAG AA`() {
        // Blue Grey and Pink sit a hundredth above the crossover, where the choice is tightest:
        // black gives 4.8:1 on both, while white — what a midpoint rule picks — gives 4.37:1
        // and 4.35:1, under AA.
        listOf("Blue Grey", "Pink").forEach { name ->
            val colour = paletteColor(name)
            assertEquals(Color.Black, colour.contrastingForeground(), "$name sits just above the crossover")
            assertTrue(contrastRatio(Color.Black, colour) >= 4.5, "$name is unreadable under black")
            assertTrue(contrastRatio(Color.White, colour) < 4.5, "$name is readable under white after all")
        }
    }

    @Test
    fun `every palette colour gets the ink with the higher contrast, and it clears WCAG AA`() {
        ProjectColorPalette.all.forEach { argb ->
            val colour = Color(argb)
            val name = ProjectColorPalette.nameOf(argb)
            val foreground = colour.contrastingForeground()
            val other = if (foreground == Color.Black) Color.White else Color.Black

            assertTrue(
                foreground == Color.Black || foreground == Color.White,
                "palette colour $name got a foreground that is neither black nor white",
            )
            assertTrue(
                contrastRatio(foreground, colour) >= 4.5,
                "palette colour $name under $foreground is ${contrastRatio(foreground, colour)}:1, " +
                    "below WCAG AA",
            )
            assertTrue(
                contrastRatio(foreground, colour) >= contrastRatio(other, colour),
                "palette colour $name is more readable under $other",
            )
        }
    }

    private fun paletteColor(name: String): Color {
        val argb =
            ProjectColorPalette.all.firstOrNull { ProjectColorPalette.nameOf(it) == name }
                ?: error("the project palette no longer has a colour named $name")
        return Color(argb)
    }

    /** WCAG 2.1 contrast ratio: `(lighter + 0.05) / (darker + 0.05)`. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val lighter = max(a.luminance(), b.luminance()).toDouble()
        val darker = min(a.luminance(), b.luminance()).toDouble()
        return (lighter + 0.05) / (darker + 0.05)
    }
}
