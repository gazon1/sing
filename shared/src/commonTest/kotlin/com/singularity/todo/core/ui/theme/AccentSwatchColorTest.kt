package com.singularity.todo.core.ui.theme

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Settings → Interface offers eight accents as coloured swatches, and a swatch is
 * a promise about what the app will look like.
 *
 * That promise used to be false for all eight: the swatches painted the seed
 * (`#FFEB3B` for yellow, `#2196F3` for blue) while the theme painted a tonal
 * palette derived from that seed, so picking yellow gave a dark olive-brown and
 * picking blue gave a slate. Both are Material's doing — the seed sets a hue, not
 * a fill — so the only honest swatch is the palette's `primary`, which is what
 * this test pins.
 *
 * What it cannot see: the picker's one line that reads [accentPrimaryColor]. That
 * line is reviewed rather than measured; what is measured here is the fact that
 * makes the seed wrong — for every accent, in both modes, the colour the app
 * paints is not the colour the seed names.
 */
@Tag("fast")
class AccentSwatchColorTest {
    @Test
    fun `the colour a swatch shows is the one the theme paints, not the seed`() {
        SingularityAccents.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val mode = if (isDark) "dark" else "light"
                val swatch = accentPrimaryColor(accent = accent, isDark = isDark)

                assertEquals(
                    accentColorScheme(accent = accent, isDark = isDark).primary,
                    swatch,
                    "${accent.name} $mode: the swatch left the palette the theme paints from, " +
                        "so the picker and the app can disagree again.",
                )
                assertNotEquals(
                    accent.color,
                    swatch,
                    "${accent.name} $mode: the swatch is the seed, and the theme paints a palette " +
                        "derived from it. If this ever becomes true, painting the seed is honest " +
                        "again — until then, a seed-coloured swatch promises a colour the user never sees.",
                )
            }
        }
    }
}
