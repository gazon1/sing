@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
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
 * A check that cannot fail is not a check, so every rule here has a control
 * beside it — a value that must trip the rule — and the controls are in this
 * file rather than in a comment about them:
 *
 * - `the icon box check sees a sequence the font draws as two glyphs` puts the
 *   same characters without their joiner (`🧑💻`) into the same box and expects
 *   them to be cut at the edge, next to a plain text value that is too wide for
 *   the box on any font. Without those, "no ink at the edge" would also hold on
 *   a box whose clipping was never detected.
 * - `the picker check sees a sequence that wraps onto a second line` puts the
 *   same unjoined sequence into the picker's chip and expects its ink to reach
 *   lower than a single emoji's — the outcome the chip rule forbids for a
 *   composed sequence.
 *
 * What it does **not** fingerprint is which glyph the font chose: two different
 * composed renderings both paint inside the box, and telling them apart needs a
 * golden image, not a rule. A font without the sequence draws one tofu box,
 * which also paints inside the box — the ink rule cannot catch that. The bound
 * is stated rather than hidden.
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
                val ink = measureBox(unmergedNodeOfTag(boxTag(index)))

                assertTrue(ink.count > 0, "'$sequence' painted nothing inside the icon box")
                assertTrue(
                    !ink.borderInk,
                    "'$sequence' is clipped at the icon box's edge — the font drew it wider than one glyph",
                )
                val glyphCentre = (ink.left + ink.right) / 2
                assertTrue(
                    abs(glyphCentre - ink.width / 2) <= 2,
                    "'$sequence' is not centred in the icon box: ink from ${ink.left} to ${ink.right} " +
                        "in ${ink.width}px",
                )
            }
        }

    /**
     * The control: the same rule, on values it must reject.
     *
     * The rule above says "no ink at the edge of the icon box". This test is
     * what makes that sentence mean something — it puts two values into the
     * same box and expects the rule to fire on both:
     *
     * - `🧑💻`, the same two emoji as `🧑‍💻` without the joiner between them.
     *   A font that composes the sequence draws this as **two** glyphs, and two
     *   glyphs are wider than the 24dp box, so the box cuts them and the ink
     *   reaches the edge. This is the negative control the positive test needs:
     *   it is the value that shows up when the sequence stops being composed.
     * - `WWWWWWWW`, plain text that is too wide for the box on any font. It
     *   proves the edge rule fires at all, independently of what a font does
     *   with an emoji.
     *
     * The control assumes a font that draws the emoji: a font that draws
     * neither has nothing to tell the two forms apart, and this test fails
     * rather than passing quietly. A machine whose font cannot draw the
     * sequence is a machine on which the positive test above says nothing
     * either.
     */
    @Test
    fun `the icon box check sees a sequence the font draws as two glyphs`() =
        runIsolatedComposeTest {
            val values = listOf(COMPOSED, UNJOINED, TOO_WIDE_TEXT)
            setContent {
                Column {
                    values.forEachIndexed { index, sequence ->
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

            val composed = measureBox(unmergedNodeOfTag(boxTag(0)))
            val unjoined = measureBox(unmergedNodeOfTag(boxTag(1)))
            val tooWide = measureBox(unmergedNodeOfTag(boxTag(2)))

            assertTrue(
                !composed.borderInk,
                "'$COMPOSED' is clipped at the icon box's edge — the font drew it wider than one glyph",
            )
            assertTrue(
                unjoined.borderInk,
                "'$UNJOINED' was not clipped: without the joiner it is two glyphs, and two glyphs are " +
                    "wider than the box. The edge rule cannot tell a composed sequence from an " +
                    "uncomposed one",
            )
            assertTrue(
                tooWide.borderInk,
                "'$TOO_WIDE_TEXT' was not clipped: text wider than the box has to be cut at its edge, " +
                    "or the edge rule never fires at all",
            )
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
     * The control: the same rule, on a value that must break it.
     *
     * The rule above says "the composed sequence's ink does not reach lower
     * than a single emoji's". This test puts the unjoined sequence —
     * `🧑💻`, the two emoji without the joiner — into the chip and expects its
     * ink to reach **lower** than a single emoji's, by more than the same
     * 10% tolerance. Two glyphs do not fit the chip's 24dp of content, so they
     * wrap onto a second line and the ink moves down; that is the failure the
     * rule above is there to catch, so the rule can catch it.
     */
    @Test
    fun `the picker check sees a sequence that wraps onto a second line`() =
        runIsolatedComposeTest {
            setContent {
                ScreenWithOwners {
                    IconPickerSheet(
                        currentIcon = null,
                        recentEmojis = persistentListOf(COMPOSED, UNJOINED, SINGLE),
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }

            val single = inkBox(topMostNodeWithText(SINGLE))
            val unjoined = inkBox(topMostNodeWithText(UNJOINED))

            val tolerance = (single.height * INK_TOLERANCE).roundToInt()
            assertTrue(
                unjoined.bottom > single.bottom + tolerance,
                "'$UNJOINED' did not wrap in the chip: its ink reaches ${unjoined.bottom}, a single " +
                    "emoji's ${single.bottom}. Two glyphs do not fit the chip's content width, so the " +
                    "rule above would not have caught them either",
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
        val backdrop = backdropColour(pixels)
        var count = 0
        var top = pixels.height
        var bottom = -1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (!differsFrom(pixels[x, y], backdrop)) continue
                count++
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return InkBox(count = count, top = top, bottom = bottom, height = pixels.height)
    }

    /**
     * The backdrop of a node: the colour its corner has.
     *
     * The chip is a circle on a transparent sheet with the glyph in the
     * middle, so the corner is the backdrop — and the check that it really is
     * the backdrop (it covers more than half of the node) is what keeps a
     * glyph that reached the corner from turning the whole node into ink.
     */
    private fun backdropColour(pixels: PixelMap): Color {
        val corner = pixels[0, 0].toArgb()
        var matching = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].toArgb() == corner) matching++
            }
        }
        assertTrue(
            matching * 2 > pixels.width * pixels.height,
            "the node's corner is not its backdrop — it covers $matching of " +
                "${pixels.width * pixels.height} pixels, so the ink cannot be read",
        )
        return Color(corner)
    }

    /**
     * The ink of an icon box: the painted pixels, how far they reach to the
     * left and to the right, and whether they touch an edge.
     *
     * The box has a background of its own here, so the backdrop is known and
     * only one pass over the pixels is needed.
     */
    private fun measureBox(node: SemanticsNodeInteraction): BoxInk {
        val pixels = node.captureToImage().toPixelMap()
        var count = 0
        var left = pixels.width
        var right = -1
        var borderInk = false
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (!isInk(pixels[x, y])) continue
                count++
                if (x < left) left = x
                if (x > right) right = x
                val onVerticalEdge = x == 0 || x == pixels.width - 1
                val onHorizontalEdge = y == 0 || y == pixels.height - 1
                if (onVerticalEdge || onHorizontalEdge) borderInk = true
            }
        }
        return BoxInk(count = count, left = left, right = right, width = pixels.width, borderInk = borderInk)
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
        assertTrue(nodes.isNotEmpty(), "no node shows the text '$text'")
        val topmost = nodes.indices.minBy { nodes[it].positionInRoot.y }
        return collection[topmost]
    }

    private data class InkBox(val count: Int, val top: Int, val bottom: Int, val height: Int)

    private data class BoxInk(val count: Int, val left: Int, val right: Int, val width: Int, val borderInk: Boolean)

    private fun isInk(color: Color): Boolean =
        abs(color.red - BACKGROUND.red) +
            abs(color.green - BACKGROUND.green) +
            abs(color.blue - BACKGROUND.blue) > INK_THRESHOLD

    private fun differsFrom(
        color: Color,
        backdrop: Color,
    ): Boolean =
        abs(color.red - backdrop.red) +
            abs(color.green - backdrop.green) +
            abs(color.blue - backdrop.blue) +
            abs(color.alpha - backdrop.alpha) > INK_THRESHOLD

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

        /**
         * The same two emoji without the joiner between them: a font that
         * composes the sequence draws this as two glyphs, and two glyphs do
         * not fit the box or the chip. It is the control the checks above need.
         */
        const val UNJOINED = "🧑💻"

        /** A single emoji, the yardstick the composed chip's ink is read against. */
        const val SINGLE = "😀"

        /**
         * Plain text that does not fit the chip's 24dp of content width, so it
         * wraps and proves that the chip can show a second line at all.
         */
        const val WRAPPING_CONTROL = "abcdefgh"

        /** Plain text that is too wide for the icon box on any font. */
        const val TOO_WIDE_TEXT = "WWWWWWWW"

        /** The composed sequences the icon box is checked with. */
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
