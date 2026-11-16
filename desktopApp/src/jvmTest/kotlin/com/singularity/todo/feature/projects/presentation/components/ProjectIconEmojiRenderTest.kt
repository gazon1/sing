@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unmergedNodeOfTag
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The project glyph as the desktop platform actually draws it.
 *
 * The Android `ProjectIconEmojiRenderTest` proves the same thing on a device,
 * and an Android screenshot says nothing about the desktop: the two platforms
 * use different fonts, and a font that composes a sequence on one platform can
 * draw it as two glyphs on the other. So the desktop has its own check.
 *
 * The assertions are about the pixels inside the icon box:
 *
 * - the box has ink, so a glyph was painted and not an empty box;
 * - no ink touches the box's edge, so the drawn glyph was not clipped — which
 *   is what a sequence the font refuses to compose looks like (two glyphs are
 *   wider than the box and get cut at its edge);
 * - the ink is centred, so the glyph sits in the middle of the box rather than
 *   hanging off one side.
 *
 * It does not fingerprint which glyph the font chose: two different composed
 * renderings both paint inside the box, and telling them apart needs a golden
 * image, not a rule. A font without the sequence draws one tofu box, which also
 * paints inside the box — the ink rule cannot catch that. The bound is stated
 * rather than hidden.
 */
@Tag("slow")
@Tag("slow:compose")
class ProjectIconEmojiRenderTest {
    @Test
    fun `a composed sequence is drawn as one glyph inside the icon box`() =
        runIsolatedComposeTest {
            setContent {
                Column {
                    SEQUENCES.forEachIndexed { index, sequence ->
                        Box(
                            modifier =
                                Modifier
                                    .size(ICON_BOX)
                                    .background(BACKGROUND)
                                    .testTag(boxTag(index)),
                        ) {
                            ProjectIcon(icon = Emoji(sequence), tint = Color.Black, size = ICON_BOX)
                        }
                    }
                }
            }

            SEQUENCES.forEachIndexed { index, sequence ->
                val pixels = unmergedNodeOfTag(boxTag(index)).captureToImage().toPixelMap()
                var ink = 0
                var borderInk = false
                var left = pixels.width
                var right = -1
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        if (!isInk(pixels[x, y])) continue
                        ink++
                        if (x < left) left = x
                        if (x > right) right = x
                        val onVerticalEdge = x == 0 || x == pixels.width - 1
                        val onHorizontalEdge = y == 0 || y == pixels.height - 1
                        if (onVerticalEdge || onHorizontalEdge) {
                            borderInk = true
                        }
                    }
                }

                assertTrue(ink > 0, "'$sequence' painted nothing inside the icon box")
                assertTrue(
                    !borderInk,
                    "'$sequence' is clipped at the icon box's edge — the font drew it wider than one glyph",
                )
                val glyphCentre = (left + right) / 2
                assertTrue(
                    abs(glyphCentre - pixels.width / 2) <= 2,
                    "'$sequence' is not centred in the icon box: ink from $left to $right in ${pixels.width}px",
                )
            }
        }

    /**
     * The picker paints a composed sequence on one line.
     *
     * The icon surface is not the only place a project emoji is drawn: the
     * picker shows it as a chip, and a chip that shows a person technologist as
     * two glyphs is a different defect from the one the icon box would show.
     *
     * What is measured, and how:
     *
     * - the chip paints ink — something was drawn;
     * - the ink's **lower edge** does not fall below the single emoji chip's.
     *   The chip is a fixed 40dp box, so a sequence that does not fit cannot
     *   make the node taller: it wraps onto a second line *inside* the same
     *   box, and what the second line moves is the ink, not the node. Reading
     *   the node's height cannot fail — the box pins it to 40dp whatever the
     *   font does, which is why the earlier version of this check was
     *   vacuous;
     * - a control chip of plain text **does** wrap in the same box, so the
     *   chip's content width really is narrow enough to break a line. Without
     *   the control, the assertion above would also hold on a chip that could
     *   never wrap, and would prove nothing.
     *
     * The bound is 10% of the chip's height. The ink of a second line lands
     * about 15% lower than the first one's, so the margin separates the two
     * cases without pinning the test to one font's metrics.
     *
     * What it does **not** check is that the chip was not clipped: the chip's
     * own bounds are the text's bounds, so a clipped glyph would be cut at the
     * edge of the very image the check reads. The icon-box test above is where
     * the clipping rule lives, because there the box is bigger than the text.
     */
    @Test
    fun `the picker paints a composed sequence`() =
        runIsolatedComposeTest {
            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        recentEmojis = persistentListOf(COMPOSED, SINGLE, WRAPPING_CONTROL),
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }

            val composed = inkBox(topMostNodeWithText(COMPOSED))
            val single = inkBox(topMostNodeWithText(SINGLE))
            val control = inkBox(topMostNodeWithText(WRAPPING_CONTROL))

            assertTrue(composed.count > 0, "the picker chip painted nothing for the composed sequence")

            val tolerance = (composed.height * INK_TOLERANCE).roundToInt()
            assertTrue(
                composed.bottom <= single.bottom + tolerance,
                "the composed sequence did not stay on one line: its ink reaches " +
                    "${composed.bottom} in the chip, a single emoji's reaches ${single.bottom}",
            )
            assertTrue(
                control.bottom > single.bottom + tolerance,
                "the control text did not wrap in the same chip — its ink reaches " +
                    "${control.bottom}, a single emoji's ${single.bottom}. The chip is wide enough " +
                    "for one line, so the composed sequence's single line proves nothing",
            )
        }

    /**
     * The painted ink of a node: how many pixels differ from the backdrop, and
     * how far down the node they reach.
     *
     * The chip has no background of its own, so the backdrop is the colour
     * that covers most of the chip's box — whatever it is on this theme.
     * Everything that differs from it is the glyph.
     */
    private fun inkBox(node: SemanticsNodeInteraction): InkBox {
        val pixels = node.captureToImage().toPixelMap()
        val colours =
            buildList {
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        add(pixels[x, y].toArgb())
                    }
                }
            }
        val backdrop =
            Color(
                colours
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }!!
                    .key,
            )
        var count = 0
        var top = pixels.height
        var bottom = -1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = Color(pixels[x, y].toArgb())
                val distance =
                    abs(color.red - backdrop.red) +
                        abs(color.green - backdrop.green) +
                        abs(color.blue - backdrop.blue) +
                        abs(color.alpha - backdrop.alpha)
                if (distance <= INK_THRESHOLD) continue
                count++
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return InkBox(count = count, top = top, bottom = bottom, height = pixels.height)
    }

    /**
     * The topmost node showing [text].
     *
     * The sheet shows the recent row above the catalog's grid, and the catalog
     * contains some of the same emoji, so a plain text search finds two
     * chips. The recent row is the one that is higher up.
     */
    private fun SemanticsNodeInteractionsProvider.topMostNodeWithText(text: String): SemanticsNodeInteraction {
        val collection = onAllNodesWithText(text, useUnmergedTree = true)
        val nodes = collection.fetchSemanticsNodes()
        val topmost = nodes.indices.minByOrNull { nodes[it].positionInRoot.y } ?: 0
        return collection[topmost]
    }

    private data class InkBox(val count: Int, val top: Int, val bottom: Int, val height: Int)

    private fun isInk(color: Color): Boolean =
        abs(color.red - BACKGROUND.red) +
            abs(color.green - BACKGROUND.green) +
            abs(color.blue - BACKGROUND.blue) > INK_THRESHOLD

    private fun boxTag(index: Int) = "project_icon_emoji_$index"

    private companion object {
        val ICON_BOX = 24.dp

        /**
         * Magenta: none of the sequences contains it, so any other pixel is
         * ink from the glyph.
         */
        val BACKGROUND = Color.Magenta

        const val INK_THRESHOLD = 0.1f

        /**
         * How much lower the ink of a second line may reach, as a share of the
         * chip's height. The measured gap between the first line's ink and the
         * second one's is about 15% of the chip.
         */
        const val INK_TOLERANCE = 0.1f

        /** The composed sequence the picker's chip is checked with. */
        const val COMPOSED = "🧑‍💻"

        /** A single emoji, the yardstick the composed chip's ink is read against. */
        const val SINGLE = "😀"

        /**
         * Plain text that does not fit the chip's 24dp of content width, so it
         * wraps and proves that the chip can show a second line at all.
         */
        const val WRAPPING_CONTROL = "abcdefgh"

        val SEQUENCES =
            listOf(
                "🧑‍💻", // ZWJ sequence — the imported person technologist
                "🇬🇧", // two regional indicators
                "🧘🏽", // skin-tone modifier
                "🕊️", // variation selector
                "👨‍👩‍👧‍👦", // ZWJ family
                "1️⃣", // keycap
            )
    }
}
