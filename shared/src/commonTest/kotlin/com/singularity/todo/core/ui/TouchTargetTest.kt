package com.singularity.todo.core.ui

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The touch-target minimum does not move silently.
 *
 * ## The defect this catches
 *
 * The minimum was 44 dp and became 48 dp, and the change was correct in itself —
 * Material's minimum interactive size is 48 dp. What was missing was any record of
 * it: the token had no comment saying what it was, and no test asserted it, so a
 * later edit could move it again without anything saying so.
 *
 * The silence was not theoretical. The size was read as `MinTouchTarget -
 * TaskListSpacing.Md` in one place, and that expression moved with the token: the
 * swipe-delete icon went from 32 dp to 36 dp silently, while the comment next to
 * it still promised 32 dp. A number that is computed is a number nobody reads.
 *
 * ## What the test says
 *
 * The minimum is at least 48 dp. Not "is 48 dp" — at least — because the rule is a
 * floor: a control may be bigger, and the floor is what the accessibility
 * guideline states. A test that pinned the exact value would fail the day
 * somebody raised it on purpose, which is the wrong reason to fail.
 */
@Tag("fast")
class TouchTargetTest {

    @Test
    fun `the touch target minimum is at least the material minimum`() {
        assertTrue(
            TouchTarget.MinSize >= 48.dp,
            "the touch-target minimum is ${TouchTarget.MinSize.value}dp — below " +
                "Material's 48 dp minimum, so a control sized to it is a target a " +
                "finger cannot hit",
        )
    }
}
