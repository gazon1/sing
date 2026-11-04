@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The touch-target helper is measured, not inferred from the source text.
 *
 * `CODING_STANDARDS.md` makes two claims: `Modifier.touchTarget()` expands a
 * node to `TouchTarget.MinSize` without changing the content's own size, and a
 * `size(...)` applied *before* it caps the node back to the visual size.
 * `scripts/check-touch-targets.py` can only read the shape of the chain: it
 * never lays a modifier out, so a helper that measured its content instead of
 * expanding it, expanded to the wrong minimum, or shrank a node that was
 * already large enough would leave the gate — and the constant's own test —
 * green. These nodes are the measurement that says otherwise: the bounds come
 * from the laid-out tree, so each promise fails here on its own.
 *
 * Every chain below is built one way round on purpose. The order cases are
 * each other's control: `touchTarget().size(20.dp)` and
 * `size(20.dp).touchTarget()` differ only in the order, so a test that passed
 * on both would be measuring the presence of the helper rather than what it
 * does.
 *
 * The bounds come from `unclippedBoundsOfTag`, declared in
 * `test/helpers/DesktopAssertions.kt` in this same `jvmTest` source set — the
 * same reader the calendar, menu and plans position tests use, so the helper
 * is part of a source set that already compiles, not something this file has
 * to bring with it.
 *
 * Tagged `slow` + `slow:compose` because a Compose UI harness counts as slow by
 * the tag convention — it mounts a real window and can block — and every
 * sibling desktop compose test carries the same pair. The `slow` tag is the
 * primary one, so the `full` population (`fast,slow`, the nightly and the
 * `run-full-tests` label) selects this class, and
 * `scripts/check-slow-tests-ran.py` fails a full run whose report does not
 * contain it — the test is not merely tagged, it is expected to have executed.
 * `slow:compose` is the sub-tag a person changing the Compose harness asks for
 * by name; it never replaces the primary tag.
 */
@Tag("slow")
@Tag("slow:compose")
class TouchTargetMeasurementTest {
    @Test
    fun `the expansion lays a small node out at the minimum and leaves the content alone`() =
        runIsolatedComposeTest {
            setContent {
                Box(Modifier.testTag(EXPANDED).touchTarget().size(SMALL)) {
                    Box(Modifier.testTag(CONTENT).size(SMALL))
                }
            }

            val expanded = unclippedBoundsOfTag(EXPANDED)
            assertDp(TouchTarget.MinSize, expanded.widthDp(), "width of the expanded node")
            assertDp(TouchTarget.MinSize, expanded.heightDp(), "height of the expanded node")

            val content = unclippedBoundsOfTag(CONTENT)
            assertDp(SMALL, content.widthDp(), "width of the content")
            assertDp(SMALL, content.heightDp(), "height of the content")
        }

    @Test
    fun `the expansion before a fixed size keeps the hit area and after it is capped`() =
        runIsolatedComposeTest {
            setContent {
                Column {
                    Box(Modifier.testTag(EXPANDED).touchTarget().size(SMALL))
                    Box(Modifier.testTag(CAPPED).size(SMALL).touchTarget())
                }
            }

            val expanded = unclippedBoundsOfTag(EXPANDED)
            assertDp(TouchTarget.MinSize, expanded.widthDp(), "width of the expanded node")
            assertDp(TouchTarget.MinSize, expanded.heightDp(), "height of the expanded node")

            val capped = unclippedBoundsOfTag(CAPPED)
            assertDp(SMALL, capped.widthDp(), "width of the node a leading size caps")
            assertDp(SMALL, capped.heightDp(), "height of the node a leading size caps")
        }

    @Test
    fun `the expansion centres the content instead of pushing it to an edge`() =
        runIsolatedComposeTest {
            setContent {
                Box(Modifier.testTag(EXPANDED).touchTarget().size(SMALL)) {
                    Box(Modifier.testTag(CONTENT).size(SMALL))
                }
            }

            val expanded = unclippedBoundsOfTag(EXPANDED)
            val content = unclippedBoundsOfTag(CONTENT)
            val inset = (TouchTarget.MinSize - SMALL) / 2

            assertDp(inset, content.left - expanded.left, "left inset of the content")
            assertDp(inset, content.top - expanded.top, "top inset of the content")
        }

    @Test
    fun `a node already larger than the minimum is not shrunk`() =
        runIsolatedComposeTest {
            setContent {
                Box(Modifier.testTag(LARGE).touchTarget().size(LARGE_SIZE))
            }

            val large = unclippedBoundsOfTag(LARGE)
            assertDp(LARGE_SIZE, large.widthDp(), "width of a node above the minimum")
            assertDp(LARGE_SIZE, large.heightDp(), "height of a node above the minimum")
        }

    @Test
    fun `an explicit minimum is the size applied, not the default`() =
        runIsolatedComposeTest {
            setContent {
                Box(Modifier.testTag(RAISED).touchTarget(RAISED_MIN).size(SMALL))
            }

            val raised = unclippedBoundsOfTag(RAISED)
            assertDp(RAISED_MIN, raised.widthDp(), "width with an explicit minimum")
            assertDp(RAISED_MIN, raised.heightDp(), "height with an explicit minimum")
        }

    /**
     * Bounds travel through pixels before they come back as dp, so an exact
     * float comparison would be an assertion about the test window's density.
     * Half a dp is far below any difference the rule cares about (48 against
     * 20, or 60 against 48) and far above the conversion error.
     */
    private fun assertDp(expected: Dp, actual: Dp, what: String) {
        assertEquals(expected.value, actual.value, 0.5f, what)
    }

    private fun DpRect.widthDp(): Dp = right - left

    private fun DpRect.heightDp(): Dp = bottom - top

    private companion object {
        const val EXPANDED = "touch-target-expanded"
        const val CAPPED = "touch-target-capped"
        const val CONTENT = "touch-target-content"
        const val LARGE = "touch-target-large"
        const val RAISED = "touch-target-raised"
        val SMALL = 20.dp
        val LARGE_SIZE = 64.dp
        val RAISED_MIN = 60.dp
    }
}
