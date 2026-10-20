package com.singularity.todo.feature.flows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import com.singularity.todo.test.helpers.a11yScan
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accessibility scan reports what a screen reader would actually meet.
 *
 * ## The rule
 *
 * A clickable that lies **outside the visible area of its root** is not
 * reported. The root's bounds are the window; a node entirely outside that
 * rectangle was scrolled out of a row, parked off to the left with a closed
 * drawer, or clipped below the fold of a sheet — and no screen reader ever
 * reaches it.
 *
 * The rule is about *visibility*, not about the node being harmless: a node
 * that overlaps the window is reported even when it is clipped at the edge,
 * because the visible part of it is still on the screen.
 *
 * ## Why it is not an allowlist
 *
 * The point is the rule, not the instance. A closed drawer parked at
 * `x = -1024` and a sheet row clipped below a 768px window are two different
 * false positives with the same cause; both are skipped because neither is on
 * the screen, and every other unlabelled clickable is still reported. The
 * second half of this test is the guarantee: the scan keeps failing on a
 * visible unlabelled clickable.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class A11yVisibilityRuleTest {

    /**
     * The visible clickable is reported, the off-screen one is not.
     *
     * Two unlabelled clickables, one inside the window and one entirely to the
     * left of it. The scan must report exactly one of them, and it must be the
     * one a user could reach.
     */
    @Test
    fun only_clickables_on_screen_are_reported() = runIsolatedComposeTest {
        setContent {
            Box(Modifier.size(400.dp)) {
                // Off screen: the whole node lies left of the window.
                Box(
                    Modifier
                        .offset(x = (-300).dp)
                        .size(60.dp)
                        .background(Color.Red)
                        .clickable { },
                )
                // On screen, and just as unlabelled as the one above.
                Box(
                    Modifier
                        .offset(x = 20.dp)
                        .size(60.dp)
                        .background(Color.Blue)
                        .clickable { },
                )
            }
        }

        val scan = a11yScan()

        assertEquals(
            1,
            scan.violations.size,
            "exactly one of the two unlabelled clickables is on screen, so " +
                "exactly one may be reported; the scan reported " +
                "${scan.violations} and skipped ${scan.offScreenSkipped}.",
        )
        assertEquals(
            1,
            scan.offScreenSkipped,
            "the off-screen clickable must be counted as skipped, not reported.",
        )
        assertTrue(
            scan.violations.single().contains("(50.0, 30.0)"),
            "the reported violation must be the on-screen one (its centre is at " +
                "x=50, y=30); the scan reported ${scan.violations.single()}",
        )
    }
}
