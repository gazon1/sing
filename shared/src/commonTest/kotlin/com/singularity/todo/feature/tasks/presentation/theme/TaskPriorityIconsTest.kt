package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.accentColorScheme
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The five priority levels have to be tellable apart — and tellable apart
 * without reading the colour.
 *
 * The detail screen signals priority with a glyph and a tint, and the tint is
 * free to say one thing only: whether the level is an exception. So the level
 * itself lives in the shape, and this test holds the whole scale to that:
 *
 * - no two levels share a glyph, so the level survives a monochrome screen;
 * - the two exception levels differ in shape and not only in colour;
 * - the default level is not painted like an exception;
 * - every glyph is legible on the surface the priority row stands on.
 */
@Tag("fast")
class TaskPriorityIconsTest {
    @Test
    fun `every level has its own glyph`() {
        val glyphs = TaskPriority.entries.map { priorityIcon(it) }

        assertEquals(
            TaskPriority.entries.size,
            glyphs.distinct().size,
            "two priority levels share a glyph: " +
                TaskPriority.entries.zip(glyphs).joinToString { (level, glyph) -> "$level=$glyph" },
        )
    }

    @Test
    fun `the exception levels differ in shape and not only in colour`() {
        val high = priorityIcon(TaskPriority.High)
        val urgent = priorityIcon(TaskPriority.Urgent)

        assertNotEquals(high, urgent, "High and Urgent are told apart by colour alone")
    }

    @Test
    fun `the default level is not painted like an exception`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"
                val medium = priorityIconTintOf(scheme, TaskPriority.Medium)

                assertNotEquals(
                    scheme.error,
                    medium,
                    "$mode: Medium wears the error role, so the default level looks like an alarm",
                )
                assertNotEquals(
                    scheme.primary,
                    medium,
                    "$mode: Medium wears the accent role, so the default level looks marked",
                )
            }
        }
    }

    @Test
    fun `only the exception levels wear the error role`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"

                listOf(TaskPriority.High, TaskPriority.Urgent).forEach { level ->
                    assertEquals(
                        scheme.error,
                        priorityIconTintOf(scheme, level),
                        "$mode: $level is an exception and wears the error role",
                    )
                }
                listOf(TaskPriority.Low, TaskPriority.Medium).forEach { level ->
                    assertEquals(
                        scheme.onSurface,
                        priorityIconTintOf(scheme, level),
                        "$mode: $level is ordinary and wears the ordinary text colour",
                    )
                }
            }
        }
    }

    @Test
    fun `every priority glyph is legible on the surface the row stands on`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = accentColorScheme(accent, isDark)
                val mode = "${accent.name} ${if (isDark) "dark" else "light"}"

                TaskPriority.entries.forEach { level ->
                    val tint = priorityIconTintOf(scheme, level)
                    val ratio = contrastRatio(tint, scheme.background)
                    assertTrue(
                        ratio >= 3.0,
                        "$mode: the $level glyph on the editor's background = $ratio < 3.0",
                    )
                }
            }
        }
    }

    @Test
    fun `every level has a caption of its own`() {
        val labels = TaskPriority.entries.map { priorityLabel(it) }

        assertEquals(
            TaskPriority.entries.size,
            labels.distinct().size,
            "two priority levels share a caption: " +
                TaskPriority.entries.zip(labels).joinToString { (level, label) -> "$level=$label" },
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
