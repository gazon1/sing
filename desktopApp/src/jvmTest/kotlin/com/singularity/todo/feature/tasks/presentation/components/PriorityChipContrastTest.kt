@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.list.priorityColor
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The priority label, measured for contrast.
 *
 * Medium is amber, and amber on a light surface measures about 1.5:1 — text
 * drawn in it is unreadable (F10 of the 2026-11-09 comparison). The priority is
 * therefore drawn as a shape, and the word beside it is drawn in a theme colour.
 * This test is what keeps that from drifting back.
 *
 * **The colour is read from the pixels, not from the source.** A test that read
 * `MaterialTheme.colorScheme.onSurfaceVariant` and compared it with the amber
 * would prove that the theme has a readable colour — not that the chip uses it.
 * The pixels of the drawn label are the thing the reader sees, so they are the
 * thing that is measured: the label's own pixels against the surface behind
 * them.
 */
@Tag("slow")
@Tag("slow:compose")
class PriorityChipContrastTest {
    @Test
    fun `the priority label is drawn in a readable colour on a light surface`() =
        runIsolatedComposeTest {
            val (label, surface) = chipColors(ThemeMode.Light)

            assertNotEquals(
                priorityColor(TaskPriority.Medium),
                label,
                "the priority colour is not a text colour — the label must use a theme colour",
            )
            assertTrue(
                contrastRatio(label, surface) >= AA_NORMAL_TEXT,
                "the label's contrast on the light surface is ${contrastRatio(label, surface)}",
            )
        }

    @Test
    fun `the priority label is drawn in a readable colour on a dark surface`() =
        runIsolatedComposeTest {
            val (label, surface) = chipColors(ThemeMode.Dark)

            assertNotEquals(
                priorityColor(TaskPriority.Medium),
                label,
                "the priority colour is not a text colour — the label must use a theme colour",
            )
            assertTrue(
                contrastRatio(label, surface) >= AA_NORMAL_TEXT,
                "the label's contrast on the dark surface is ${contrastRatio(label, surface)}",
            )
        }

    @Test
    fun `the priority label is drawn in a readable colour on a pinned card`() =
        runIsolatedComposeTest {
            // A pinned card is not the plain surface: it is the surface with a
            // tinted overlay on it, so the label is measured against what it
            // really stands on.
            val (label, surface) = chipColors(ThemeMode.Light, pinned = true)

            assertTrue(
                contrastRatio(label, surface) >= AA_NORMAL_TEXT,
                "the label's contrast on a pinned card is ${contrastRatio(label, surface)}",
            )
        }

    @Test
    fun `the priority label is drawn in a readable colour on a pinned card in the dark theme`() =
        runIsolatedComposeTest {
            val (label, surface) = chipColors(ThemeMode.Dark, pinned = true)

            assertTrue(
                contrastRatio(label, surface) >= AA_NORMAL_TEXT,
                "the label's contrast on a pinned card is ${contrastRatio(label, surface)}",
            )
        }

    /**
     * The colour the label is painted with, and the surface it sits on.
     *
     * Both are read out of a screenshot of the chip: the surface is the colour
     * that covers most of the label's box, and the label is the pixel that
     * stands out most against it.
     *
     * @param mode the theme to render in.
     * @param pinned whether the chip sits on a pinned card — the surface with a
     *   tint over it — instead of on the plain surface.
     */
    private suspend fun DesktopComposeUiTest.chipColors(
        mode: ThemeMode,
        pinned: Boolean = false,
    ): Pair<Color, Color> {
        setContent {
            SingularityTheme(themeMode = mode) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    if (pinned) {
                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                ),
                        ) {
                            PriorityChip(priority = TaskPriority.Medium)
                        }
                    } else {
                        PriorityChip(priority = TaskPriority.Medium)
                    }
                }
            }
        }

        val pixels = onAllNodesWithText(LABEL)[0].captureToImage().toPixelMap()
        val colours =
            buildList {
                for (x in 0 until pixels.width) {
                    for (y in 0 until pixels.height) {
                        add(pixels[x, y])
                    }
                }
            }
        check(colours.isNotEmpty()) { "the label's box is empty — nothing was drawn" }

        val surface =
            colours
                .groupingBy { it.toArgb() }
                .eachCount()
                .maxByOrNull { it.value }!!
                .key
                .let { Color(it) }
        val label = colours.maxByOrNull { contrastRatio(it, surface) }!!
        return label to surface
    }

    /** The WCAG contrast ratio between two opaque colours. */
    private fun contrastRatio(
        a: Color,
        b: Color,
    ): Float {
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + 0.05f) / (min(first, second) + 0.05f)
    }

    /** The relative luminance of an sRGB colour, as WCAG defines it. */
    private fun luminance(color: Color): Float {
        fun channel(value: Float): Float =
            if (value <= 0.03928f) {
                value / 12.92f
            } else {
                ((value + 0.055f) / 1.055f).pow(2.4f)
            }
        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }

    private companion object {
        /** WCAG AA for normal text. */
        const val AA_NORMAL_TEXT = 4.5f

        /** What the chip calls Medium — the `priority_word_medium` resource. */
        const val LABEL = "Medium"
    }
}
