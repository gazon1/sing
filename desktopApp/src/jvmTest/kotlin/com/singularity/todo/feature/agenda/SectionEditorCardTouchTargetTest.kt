@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.agenda

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TouchTarget
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.presentation.components.SectionEditorCard
import com.singularity.todo.test.helpers.clickContentDescriptionAtOffset
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfContentDescription
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three buttons of [SectionEditorCard] are pointer targets, and this measures
 * them as such.
 *
 * The contract the app holds itself to is a real 48 × 48 dp clickable with a smaller
 * glyph centred inside it. Neither half can be read off the source: a chain that
 * *reserves* 48 dp of layout space around a 40 dp clickable leaves the clickable
 * itself at 40 dp, and Material's `IconButton` cannot be widened at all because the
 * size it paints at sits inside its clickable. The only place the difference shows
 * is the laid-out tree — the bounds of the node that owns the click, and the bounds
 * of the glyph inside it.
 *
 * So the bounds come from the tree. The first test reads them: the clickable is
 * 48 × 48 dp and the glyph is 24 × 24 dp, centred inside it. The second test is the
 * control that gives the first one teeth — the shape the buttons used to have
 * measures 40 dp, so the 48 dp assertion would fail for it.
 *
 * The pointer is asked separately, in the third test: a tap 22 dp from the centre of
 * the button lands outside the 24 dp glyph and inside the target, and the button
 * answers it.
 *
 * Tagged `slow` + `slow:compose` because a Compose UI harness counts as slow by the
 * tag convention — it mounts a real window and can block.
 */
@Tag("slow")
@Tag("slow:compose")
class SectionEditorCardTouchTargetTest {
    @Test
    fun `each button is a 48 dp target with a smaller glyph centred inside it`() =
        runIsolatedComposeTest {
            setContent {
                MaterialTheme {
                    SectionEditorCard(
                        section = section(),
                        index = 1,
                        canMoveUp = true,
                        canMoveDown = true,
                        onMoveUp = {},
                        onMoveDown = {},
                        onDelete = {},
                    )
                }
            }

            BUTTONS.forEach { desc ->
                val target = unclippedBoundsOfContentDescription(desc)
                assertDp(TouchTarget.MinSize, target.widthDp(), "width of the $desc target")
                assertDp(TouchTarget.MinSize, target.heightDp(), "height of the $desc target")

                // The glyph is a child of the clickable and carries the description
                // itself, so it is only a node of its own in the unmerged tree.
                val glyph = unclippedBoundsOfContentDescription(desc, useUnmergedTree = true)
                assertDp(GLYPH_SIZE, glyph.widthDp(), "width of the $desc glyph")
                assertDp(GLYPH_SIZE, glyph.heightDp(), "height of the $desc glyph")

                val inset = (TouchTarget.MinSize - GLYPH_SIZE) / 2
                assertDp(inset, glyph.left - target.left, "left inset of the $desc glyph")
                assertDp(inset, glyph.top - target.top, "top inset of the $desc glyph")
            }
        }

    @Test
    fun `the old shape measures 40 dp, so the assertion above has teeth`() =
        runIsolatedComposeTest {
            setContent {
                MaterialTheme {
                    OldShapeButton()
                }
            }

            val target = unclippedBoundsOfContentDescription("Move section down")
            assertDp(OLD_TARGET_SIZE, target.widthDp(), "width of the old target")
            assertDp(OLD_TARGET_SIZE, target.heightDp(), "height of the old target")
            assertTrue(
                target.widthDp() < TouchTarget.MinSize,
                "the old shape is narrower than the minimum the first test demands, " +
                    "so the first test would fail for it",
            )
        }

    @Test
    fun `a tap outside the glyph but inside the target presses the button`() =
        runIsolatedComposeTest {
            var moves = 0
            setContent {
                MaterialTheme {
                    SectionEditorCard(
                        section = section(),
                        index = 1,
                        canMoveUp = false,
                        canMoveDown = true,
                        onMoveUp = {},
                        onMoveDown = { moves++ },
                        onDelete = {},
                    )
                }
            }

            clickContentDescriptionAtOffset("Move section down", offsetDp = -BAND_DP)

            assertEquals(1, moves, "the button answered a tap in the band around the glyph")
        }

    @Test
    fun `a disabled button does not answer a tap in its own band`() =
        runIsolatedComposeTest {
            var moves = 0
            setContent {
                MaterialTheme {
                    SectionEditorCard(
                        section = section(),
                        index = 0,
                        canMoveUp = false,
                        canMoveDown = true,
                        onMoveUp = { moves++ },
                        onMoveDown = {},
                        onDelete = {},
                    )
                }
            }

            clickContentDescriptionAtOffset("Move section up", offsetDp = -BAND_DP)

            assertEquals(0, moves, "a disabled button stays disabled")
        }

    @Test
    fun `the targets keep their size on a narrow screen`() =
        runIsolatedComposeTest(width = NARROW_WIDTH, height = NARROW_HEIGHT) {
            setContent {
                MaterialTheme {
                    SectionEditorCard(
                        section = section(),
                        index = 1,
                        canMoveUp = true,
                        canMoveDown = true,
                        onMoveUp = {},
                        onMoveDown = {},
                        onDelete = {},
                    )
                }
            }

            BUTTONS.forEach { desc ->
                val target = unclippedBoundsOfContentDescription(desc)
                assertDp(TouchTarget.MinSize, target.widthDp(), "width of the $desc target on a narrow screen")
                assertDp(TouchTarget.MinSize, target.heightDp(), "height of the $desc target on a narrow screen")
            }
        }

    /**
     * The shape the buttons had before: Material's `IconButton`, whose clickable is
     * laid out at the 40 dp it paints at, sitting in a 48 dp reservation.
     *
     * The control for the measurement above. Its *pointer* area was 48 dp anyway —
     * Compose expands the touch bounds of a small `clickable` to the platform's
     * minimum touch target — but the button's own node, the rect the semantics tree
     * reports, was 40 dp.
     */
    @Composable
    private fun OldShapeButton() {
        Box(modifier = Modifier.size(TouchTarget.MinSize), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = {},
                modifier = Modifier.minimumInteractiveComponentSize().size(OLD_TARGET_SIZE),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move section down",
                )
            }
        }
    }

    /**
     * Bounds travel through pixels before they come back as dp, so an exact float
     * comparison would be an assertion about the test window's density. Half a dp is
     * far below any difference the rule cares about (48 against 24) and far above the
     * conversion error.
     */
    private fun assertDp(
        expected: Dp,
        actual: Dp,
        what: String,
    ) {
        assertEquals(expected.value, actual.value, 0.5f, what)
    }

    private fun DpRect.widthDp(): Dp = right - left

    private fun DpRect.heightDp(): Dp = bottom - top

    private fun section() =
        Section(
            name = "Today",
            selector = Selector.Anything,
        )

    private companion object {
        val BUTTONS = listOf("Move section up", "Move section down", "Delete section")

        /** The size of the glyph inside the target. */
        val GLYPH_SIZE = 24.dp

        /** The size the clickable of the old shape was laid out at. */
        val OLD_TARGET_SIZE = 40.dp

        /**
         * How far from the centre of the target the tap lands.
         *
         * Outside the 24 dp glyph (which reaches 12 dp from the centre) and inside
         * the 48 dp target (which reaches 24 dp).
         */
        const val BAND_DP = 22f

        const val NARROW_WIDTH = 320
        const val NARROW_HEIGHT = 768
    }
}
