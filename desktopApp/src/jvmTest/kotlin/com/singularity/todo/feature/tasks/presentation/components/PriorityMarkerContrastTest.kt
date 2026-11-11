@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.list.TaskRowFlat
import com.singularity.todo.feature.tasks.presentation.components.list.priorityColor
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The priority marker, measured for contrast.
 *
 * The priority colour is a hue, not a light: Medium is amber, and amber on a
 * light surface measures about 1.5:1 — below the 3:1 that WCAG 1.4.11 asks of
 * a non-text indicator. The marker therefore wears a rim, and this test is what
 * keeps the rim there.
 *
 * **The marker is measured as it is drawn.** The test reads the pixels of the
 * marker and asks what the eye sees at the marker's edge: the ring of pixels
 * around the priority colour, against the surface the marker stands on. A
 * marker without a rim would measure the hue itself — about 1.5:1 — and fail.
 *
 * Both markers are measured, because both carry the hue: the dot on the chip
 * and the star on the agenda row.
 */
@Tag("slow")
@Tag("slow:compose")
class PriorityMarkerContrastTest {
    @Test
    fun `the chip's dot keeps the non-text contrast on a light surface`() =
        runIsolatedComposeTest {
            val marker = measureChipDot(ThemeMode.Light)

            assertTrue(
                marker.fillPixels > 0,
                "the chip's dot was not drawn in the priority colour — nothing was measured",
            )
            assertTrue(
                marker.edgeContrast >= NON_TEXT_CONTRAST,
                "the chip's dot measures ${marker.edgeContrast}:1 against the surface it " +
                    "stands on (its edge is ${marker.edgeColor}) — a non-text indicator has " +
                    "to keep 3:1",
            )
        }

    @Test
    fun `the chip's dot keeps the non-text contrast on a dark surface`() =
        runIsolatedComposeTest {
            val marker = measureChipDot(ThemeMode.Dark)

            assertTrue(
                marker.fillPixels > 0,
                "the chip's dot was not drawn in the priority colour — nothing was measured",
            )
            assertTrue(
                marker.edgeContrast >= NON_TEXT_CONTRAST,
                "the chip's dot measures ${marker.edgeContrast}:1 against the surface it " +
                    "stands on (its edge is ${marker.edgeColor}) — a non-text indicator has " +
                    "to keep 3:1",
            )
        }

    @Test
    fun `the agenda row's star keeps the non-text contrast on a light surface`() =
        runIsolatedComposeTest {
            val marker = measureRowStar(ThemeMode.Light)

            assertTrue(
                marker.fillPixels > 0,
                "the row's star was not drawn in the priority colour — nothing was measured",
            )
            assertTrue(
                marker.edgeContrast >= NON_TEXT_CONTRAST,
                "the row's star measures ${marker.edgeContrast}:1 against the surface it " +
                    "stands on (its edge is ${marker.edgeColor}) — a non-text indicator has " +
                    "to keep 3:1",
            )
        }

    @Test
    fun `the agenda row's star keeps the non-text contrast on a dark surface`() =
        runIsolatedComposeTest {
            val marker = measureRowStar(ThemeMode.Dark)

            assertTrue(
                marker.fillPixels > 0,
                "the row's star was not drawn in the priority colour — nothing was measured",
            )
            assertTrue(
                marker.edgeContrast >= NON_TEXT_CONTRAST,
                "the row's star measures ${marker.edgeContrast}:1 against the surface it " +
                    "stands on (its edge is ${marker.edgeColor}) — a non-text indicator has " +
                    "to keep 3:1",
            )
        }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** The chip's dot, as it is drawn, measured against the surface around it. */
    private suspend fun DesktopComposeUiTest.measureChipDot(mode: ThemeMode): Marker {
        setContent {
            SingularityTheme(themeMode = mode) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.testTag(CHIP_TAG)) {
                        PriorityChip(priority = TaskPriority.Medium)
                    }
                }
            }
        }

        return measure(awaitTag(CHIP_TAG).captureToImage())
    }

    /** The agenda row's star, as it is drawn, measured against the row's surface. */
    private suspend fun DesktopComposeUiTest.measureRowStar(mode: ThemeMode): Marker {
        setContent {
            SingularityTheme(themeMode = mode) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    TaskRowFlat(
                        task =
                            TaskUi(
                                id = TaskId.fromString("1"),
                                title = ROW_TITLE,
                                project = null,
                                dueLabel = null,
                                priority = TaskPriority.Medium,
                            ),
                        onToggleCompleted = {},
                        onClick = {},
                    )
                }
            }
        }

        return measure(awaitTag(TestTags.taskItem(ROW_TITLE)).captureToImage())
    }

    /**
     * What the marker measures: the surface it stands on, and the contrast of
     * the ring of pixels around the priority colour.
     *
     * The ring is the marker's own edge: the pixels that touch the priority
     * colour. A marker that is only a hue has the hue there, and the hue is
     * what fails; a marker with a rim has the rim there, and the rim is what
     * the eye finds.
     */
    private fun measure(image: ImageBitmap): Marker {
        val pixels = image.toPixelMap()
        val fillArgb = priorityColor(TaskPriority.Medium).toArgb()

        val counts = HashMap<Int, Int>()
        val fill = ArrayList<Int>()
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                val argb = pixels[x, y].toArgb()
                counts[argb] = (counts[argb] ?: 0) + 1
                if (argb == fillArgb) {
                    fill.add(x)
                    fill.add(y)
                }
            }
        }
        check(counts.isNotEmpty()) { "the capture is empty — nothing was drawn" }

        // The surface: the colour that covers most of what was captured.
        val surface = Color(counts.maxByOrNull { it.value }!!.key)

        // The ring: every pixel that touches the priority colour.
        val ring = HashSet<Int>()
        for (i in fill.indices step 2) {
            val x = fill[i]
            val y = fill[i + 1]
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val index = neighbourIndex(dx, dy, x, y, pixels.width, pixels.height)
                    if (index != null && pixels[index % pixels.width, index / pixels.width].toArgb() != fillArgb) {
                        ring.add(index)
                    }
                }
            }
        }

        var edgeContrast = 0f
        var edgeColor = Color.Transparent
        for (index in ring) {
            val colour = Color(pixels[index % pixels.width, index / pixels.width].toArgb())
            val contrast = contrastRatio(colour, surface)
            if (contrast > edgeContrast) {
                edgeContrast = contrast
                edgeColor = colour
            }
        }

        return Marker(
            surface = surface,
            fillPixels = fill.size / 2,
            edgeContrast = edgeContrast,
            edgeColor = edgeColor,
        )
    }

    /**
     * The index of the pixel next to the marker, or null when there is none to
     * look at: the centre of the marker itself, or outside the capture.
     */
    private fun neighbourIndex(
        dx: Int,
        dy: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Int? {
        if (dx == 0 && dy == 0) return null
        val nx = x + dx
        val ny = y + dy
        if (nx !in 0 until width || ny !in 0 until height) return null
        return ny * width + nx
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

    /** What the marker measures. */
    private data class Marker(
        /** The surface the marker stands on. */
        val surface: Color,
        /** How many pixels carry the priority colour. */
        val fillPixels: Int,
        /** The contrast of the marker's edge against the surface. */
        val edgeContrast: Float,
        /** The colour of that edge. */
        val edgeColor: Color,
    )

    private companion object {
        /** WCAG 1.4.11 — the contrast a non-text indicator has to keep. */
        const val NON_TEXT_CONTRAST = 3.0f

        /** The tag the chip is captured under. The chip's own dot has no tag. */
        const val CHIP_TAG = "priority_marker_chip"

        /** The row the star is measured on. */
        const val ROW_TITLE = "Call the dentist"
    }
}
