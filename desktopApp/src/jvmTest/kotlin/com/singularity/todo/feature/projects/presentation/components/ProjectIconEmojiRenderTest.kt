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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.feature.projects.presentation.components.IconPickerSheet
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unmergedNodeOfTag
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.math.abs
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
     * The picker paints a composed sequence too.
     *
     * The icon surface is not the only place a project emoji is drawn: the
     * picker shows it as a chip, and a chip that shows a person technologist as
     * two glyphs is a different defect from the one the icon box would show.
     *
     * Two things are checked, and only two:
     *
     * - the chip paints ink — something was drawn;
     * - the chip lays the sequence out on **one line**, the same height as a
     *   single emoji beside it. A font that refuses to compose the sequence
     *   lays it out as two glyphs side by side, and two glyphs do not fit the
     *   chip's 24dp of content width, so the text wraps. That is a measure of
     *   the layout, not of the shape of the glyph, which is why it is safe to
     *   assert on a machine whose fonts differ from the developer's.
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
                        recentEmojis = persistentListOf(COMPOSED, SINGLE),
                        onPick = {},
                        onDismiss = {},
                    )
                }
            }

            val composed = onAllNodesWithText(COMPOSED, useUnmergedTree = true).onFirst()
            val pixels = composed.captureToImage().toPixelMap()

            // The chip has no background of its own, so the backdrop is the
            // colour that covers most of the chip's box — whatever it is on
            // this theme. Everything that differs from it is the glyph.
            val colours =
                buildList {
                    for (y in 0 until pixels.height) {
                        for (x in 0 until pixels.width) {
                            add(pixels[x, y].toArgb())
                        }
                    }
                }
            val backdrop =
                colours
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }!!
                    .key
            val ink =
                colours.count {
                    val color = Color(it)
                    abs(color.red - Color(backdrop).red) +
                        abs(color.green - Color(backdrop).green) +
                        abs(color.blue - Color(backdrop).blue) > INK_THRESHOLD
                }
            assertTrue(ink > 0, "the picker chip painted nothing for the composed sequence")

            val composedBounds = composed.getUnclippedBoundsInRoot()
            val singleBounds =
                onAllNodesWithText(SINGLE, useUnmergedTree = true)
                    .onFirst()
                    .getUnclippedBoundsInRoot()
            val composedHeight = (composedBounds.bottom - composedBounds.top).value
            val singleHeight = (singleBounds.bottom - singleBounds.top).value
            assertTrue(
                abs(composedHeight - singleHeight) <= 2f,
                "the composed sequence is not laid out on one line: " +
                    "$composedHeight tall beside a single emoji's $singleHeight",
            )
        }

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

        /** The composed sequence the picker's chip is checked with. */
        const val COMPOSED = "🧑‍💻"

        /** A single emoji, the yardstick the composed chip's line height is read against. */
        const val SINGLE = "😀"

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
