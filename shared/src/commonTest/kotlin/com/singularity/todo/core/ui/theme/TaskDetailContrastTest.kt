package com.singularity.todo.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Task Detail reads its text and its icons off the theme, so the palette is the
 * only place where their legibility is decided.
 *
 * This test pins that decision against the **combinations the screen really
 * draws** — a role against the surface it stands on — and not against every
 * role against every surface. The difference matters: a role that is unreadable
 * on a surface nothing paints it on is not a defect, and a role that is readable
 * on the wrong surface is not a guarantee. Each pair below is a real element of
 * the screen and the surface that element stands on.
 *
 * The rule is WCAG AA: 4.5:1 for text, 3:1 for a glyph that carries meaning
 * without text (the priority marker and the set-attribute icon).
 */
@Tag("fast")
class TaskDetailContrastTest {
    @Test
    fun `the task detail's text clears AA on the surface each element stands on`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"

                textPairs(scheme).forEach { pair ->
                    val ratio = contrastRatio(pair.foreground, pair.background)
                    assertTrue(
                        ratio >= 4.5,
                        "$mode: ${pair.element} on ${pair.surfaceName} = $ratio < 4.5",
                    )
                }
            }
        }
    }

    @Test
    fun `the task detail's meaning bearing icons clear 3 to 1 on the surface they are drawn on`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"

                iconPairs(scheme).forEach { pair ->
                    val ratio = contrastRatio(pair.foreground, pair.background)
                    assertTrue(
                        ratio >= 3.0,
                        "$mode: ${pair.element} on ${pair.surfaceName} = $ratio < 3.0",
                    )
                }
            }
        }
    }

    /**
     * The text of the Task Detail, each on the surface it is painted on.
     *
     * The surfaces are the four the screen really uses: the editor's
     * `background` (the hero and the three bare rows), the `surface` of an
     * attribute card, the `surfaceVariant` of an extra section, and the
     * `primaryContainer` of a tag chip.
     */
    private fun textPairs(scheme: ColorScheme): List<Pair> {
        val muted = mutedTextColorOf(scheme)
        return listOf(
            // The hero and the three editor rows stand on the editor's background.
            Pair("the title", "background", scheme.onSurface, scheme.background),
            Pair("the title placeholder", "background", muted, scheme.background),
            Pair("the description", "background", scheme.onSurface, scheme.background),
            Pair("the description placeholder", "background", muted, scheme.background),
            Pair("the priority label", "background", scheme.onSurface, scheme.background),
            Pair("the unset priority label", "background", scheme.onSurfaceVariant, scheme.background),
            Pair("the due date label", "background", scheme.onSurface, scheme.background),
            Pair("the unset due date label", "background", scheme.onSurfaceVariant, scheme.background),
            Pair("the estimate label", "background", scheme.onSurface, scheme.background),
            Pair("the unset estimate label", "background", scheme.onSurfaceVariant, scheme.background),
            // An attribute card is a `surface` of its own.
            Pair("the attribute card label", "surface", scheme.onSurface, scheme.surface),
            Pair("the unset attribute card label", "surface", scheme.onSurfaceVariant, scheme.surface),
            // An extra section is a `surfaceVariant`, and everything inside it —
            // the header, the hint and the body — inherits that card's content colour.
            Pair("the extra section header", "surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant),
            Pair("the extra section hint", "surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant),
            Pair("the extra section body", "surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant),
            // The tag chip is the one Task Detail surface with its own fill.
            Pair("the tag chip text", "primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer),
            // The first-run banner and its chips.
            Pair("the first-run title", "tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer),
            Pair("the first-run chip label", "surface", scheme.onSurface, scheme.surface),
        )
    }

    /**
     * The icons of the Task Detail that carry meaning without text.
     *
     * The priority marker says the level with its shape and says "this is an
     * exception" with its colour, so all four of its tints have to be visible on
     * the background it stands on. The set-attribute icon is the accent on the
     * attribute card's own surface.
     */
    private fun iconPairs(scheme: ColorScheme): List<Pair> =
        listOf(
            Pair("the unset priority marker", "background", mutedTextColorOf(scheme), scheme.background),
            Pair("the low priority marker", "background", scheme.onSurface, scheme.background),
            Pair("the medium priority marker", "background", scheme.onSurface, scheme.background),
            Pair("the high priority marker", "background", scheme.error, scheme.background),
            Pair("the urgent priority marker", "background", scheme.error, scheme.background),
            Pair("the set attribute icon", "surface", scheme.primary, scheme.surface),
            Pair("the extra section icon", "surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant),
        )

    /**
     * One element of the screen: what it is, the surface it stands on, and the
     * two colours that decide whether it can be read.
     */
    private data class Pair(val element: String, val surfaceName: String, val foreground: Color, val background: Color)

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Double {
        // A translucent role is only as dark as it looks once it is painted on
        // the surface underneath, so the alpha is composited before measuring.
        val l1 = relativeLuminance(foreground.compositeOver(background))
        val l2 = relativeLuminance(background)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val r = channel(color.red)
        val g = channel(color.green)
        val b = channel(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
