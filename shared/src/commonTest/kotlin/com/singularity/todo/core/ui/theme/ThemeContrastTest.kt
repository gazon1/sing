package com.singularity.todo.core.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("fast")
class ThemeContrastTest {

    @Test
    fun `onPrimary on primary and onSurface on surface pass WCAG AA in light and dark for every accent`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = dynamicColorScheme(seedColor = accent.color, isDark = isDark, contrastLevel = 0.5)
                val primaryRatio = contrastRatio(scheme.onPrimary, scheme.primary)
                val surfaceRatio = contrastRatio(scheme.onSurface, scheme.surface)
                assertTrue(
                    primaryRatio >= 4.5,
                    "${accent.name} ${if (isDark) "dark" else "light"}: onPrimary/primary = $primaryRatio < 4.5",
                )
                assertTrue(
                    surfaceRatio >= 4.5,
                    "${accent.name} ${if (isDark) "dark" else "light"}: onSurface/surface = $surfaceRatio < 4.5",
                )
            }
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
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
