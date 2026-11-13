package com.singularity.todo.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `MUTED_TEXT_ALPHA` is a global token: the Task Detail raised it from 0.72 to
 * 0.85, and every screen that paints secondary text — the agenda card, the
 * flat list row, the settings preview — paints it too. A global change is only
 * allowed to stay when it holds up everywhere, so this test audits the token
 * against the surfaces its consumers actually stand on rather than against the
 * Task Detail alone.
 *
 * The consumers are:
 * - `TaskMetaRow` (the due date label, the separator dot),
 * - `TaskMarkingsRow` (the "+N" count, the recurring / no-date / completed marks),
 * - `ProjectBadge` (the project name),
 * - `TaskRowContent` (the title of a completed task),
 * - `TaskCheckbox` (the border of an unchecked box),
 * - `SeriesMarker` (the series icon).
 *
 * All of them are small text (12sp) on a card, so WCAG AA asks for 4.5:1 — the
 * large-text exemption does not apply to them.
 */
@Tag("fast")
class MutedTextTokenTest {
    @Test
    fun `the muted token stays readable on every surface its consumers stand on`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"
                val muted = mutedTextColorOf(scheme)

                consumerSurfaces(scheme).forEach { surface ->
                    val ratio = contrastRatio(muted, surface.color)
                    assertTrue(
                        ratio >= 4.5,
                        "$mode: muted text on ${surface.name} = $ratio < 4.5",
                    )
                }
            }
        }
    }

    @Test
    fun `the muted token stays below the primary text instead of competing with it`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"

                // The primary text of the same row is the benchmark: secondary
                // text has to be visibly weaker, otherwise a completed title
                // reads like an unfinished one. The muted colour is compared as
                // it is painted, so it is composited onto the surface first.
                val painted = mutedTextColorOf(scheme).compositeOver(scheme.background)
                val ratio = contrastRatio(painted, scheme.onSurface)
                assertTrue(
                    ratio >= 1.5,
                    "$mode: muted text against the primary text = $ratio < 1.5",
                )
            }
        }
    }

    @Test
    fun `the muted series marker and the empty checkbox border stay visible`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"

                // The series marker and the empty checkbox border carry meaning
                // without text, so they need 3:1 rather than 4.5:1.
                val marker = contrastRatio(mutedTextColorOf(scheme), scheme.surface)
                assertTrue(
                    marker >= 3.0,
                    "$mode: the series marker on the card = $marker < 3.0",
                )
            }
        }
    }

    /**
     * The surfaces the muted consumers really stand on.
     *
     * The agenda card is a `surface`; a pinned card is the `primaryContainer`
     * at a third of its strength laid over the `background`, so the tint has to
     * be composited before it is measured; the flat row and the settings
     * preview stand on the `background`.
     */
    private fun consumerSurfaces(scheme: ColorScheme): List<Surface> =
        listOf(
            Surface("the agenda card", scheme.surface),
            Surface("the background", scheme.background),
            Surface(
                "the pinned card",
                scheme.primaryContainer.copy(alpha = 0.3f).compositeOver(scheme.background),
            ),
        )

    private data class Surface(val name: String, val color: Color)

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Double {
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
