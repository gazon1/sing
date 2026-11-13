package com.singularity.todo.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Task Detail reads its text and icon roles off the theme, so the palette is the
 * only place where its legibility is decided. This test pins that decision:
 * every surface a Task Detail row can stand on, against every role it paints
 * there, for all eight accents in both light and dark.
 *
 * The rule is WCAG AA: 4.5:1 for text, 3:1 for a glyph that carries meaning
 * without text (the priority and checklist icons).
 */
@Tag("fast")
class TaskDetailContrastTest {
    @Test
    fun `text roles clear AA on every Task Detail surface for every accent and mode`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"
                val muted = mutedTextColorOf(scheme)

                // The three surfaces a Task Detail row is painted on.
                val surfaces =
                    mapOf(
                        "surface" to scheme.surface,
                        "background" to scheme.background,
                        "surfaceVariant" to scheme.surfaceVariant,
                    )

                surfaces.forEach { (surfaceName, surface) ->
                    assertTextRatio(mode, surfaceName, scheme.onSurface, surface)
                    assertTextRatio(mode, surfaceName, scheme.onSurfaceVariant, surface)
                    assertTextRatio(mode, surfaceName, muted, surface)
                }

                // The tag chip is the one Task Detail surface with its own fill.
                assertTextRatio(mode, "primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
            }
        }
    }

    @Test
    fun `icon roles clear 3 to 1 on the surfaces they are drawn on`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"

                // The priority icons and the set-attribute glyph are meaning-bearing
                // without text, so they are held to the non-text 3:1 rule.
                listOf("primary" to scheme.primary, "error" to scheme.error).forEach { (role, color) ->
                    assertNonTextRatio(mode, role, color, scheme.surface)
                    assertNonTextRatio(mode, role, color, scheme.background)
                }
            }
        }
    }

    private fun assertTextRatio(
        mode: String,
        surfaceName: String,
        foreground: Color,
        background: Color,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            ratio >= 4.5,
            "$mode: $foreground on $surfaceName = $ratio < 4.5",
        )
    }

    private fun assertNonTextRatio(
        mode: String,
        role: String,
        foreground: Color,
        background: Color,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            ratio >= 3.0,
            "$mode: $role icon on $background = $ratio < 3.0",
        )
    }

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
