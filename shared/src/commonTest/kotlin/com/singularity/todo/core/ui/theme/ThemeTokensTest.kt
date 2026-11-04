package com.singularity.todo.core.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The theme's token scales, pinned by value.
 *
 * The task feature used to carry two spacing scales and two answers for a card's
 * corner, and nothing failed when they disagreed — `xl` was 24 dp in the editor
 * and 20 dp in the list. A scale is the promise that one name means one number,
 * so the promise is asserted here instead of being left to the next reader of
 * two files to notice.
 *
 * **What this file can and cannot see.** It asserts *values*, not call sites. A
 * common-code test cannot read the `.kt` file that spends a token, so a call
 * site that stops composing and writes a bare step compiles, passes this file,
 * and is not caught here. What is asserted instead is the property that makes
 * such a drift *visible*: no step of the scale equals the off-grid values the
 * cards and the spotlight use. 14 dp and 20 dp are therefore unreachable from
 * any single name, so a bare `lg` at one of those call sites is always a
 * different, larger number (16) — never a silent re-spelling of the old one.
 *
 * The call sites are kept on the composition by the comment beside each
 * `padding(vertical = …)` and by review. That is the limit of a value test, and
 * stating the limit is cheaper than a test that reads as if it guards more.
 */
@Tag("fast")
class ThemeTokensTest {
    @Test
    fun `the spacing scale carries one step per value and climbs`() {
        val steps =
            listOf(
                AppSpacing.xxs,
                AppSpacing.xs,
                AppSpacing.sm,
                AppSpacing.md,
                AppSpacing.lg,
                AppSpacing.xl,
            )

        // One assertion on the whole list, not three: an explicit list already
        // implies distinctness and ascending order, so separate `distinct()` /
        // `sorted()` checks could not fail independently of it.
        assertEquals(listOf(2.dp, 4.dp, 8.dp, 12.dp, 16.dp, 24.dp), steps)
    }

    @Test
    fun `a task card's corner is the theme's large slot, not a second number`() {
        assertEquals(CornerSize(16.dp), AppShapes.large.topStart)
        assertEquals(CornerSize(12.dp), AppShapes.medium.topStart)
    }

    @Test
    fun `the cards keep the off-grid insets their old tokens carried`() {
        // TaskAttributeCard, LinkedBacklinksCard, both LogbookSection cards and the
        // three task-editor attribute rows (due date, estimate, priority) padded 14 dp
        // vertically before the task feature's two token sets were folded into one
        // scale. 14 dp is not a step, so those call sites compose it as md + xs.
        assertEquals(14.dp, AppSpacing.md + AppSpacing.xs, "a card's vertical inset must stay 14 dp")
        assertEquals(16.dp, AppSpacing.lg, "a card's horizontal inset must stay 16 dp")

        // The onboarding spotlight card's 20 dp inset is off-grid for the same reason.
        // `git grep 'TaskListSpacing\.Xl' origin/main -- '*.kt'` returns no hits — the
        // step was declared and never read — so the scale dropped it and the one card
        // that wants 20 dp composes it.
        assertEquals(20.dp, AppSpacing.lg + AppSpacing.xs, "the spotlight card's inset must stay 20 dp")

        // The guard that gives the comments above their teeth: because no step is 14 or
        // 20, swapping a composition for a bare `lg` changes the number instead of
        // re-spelling it. This is what a value test *can* assert on behalf of call sites.
        assertNotEquals(14.dp, AppSpacing.lg, "a bare lg must not stand in for md + xs")
        assertNotEquals(20.dp, AppSpacing.lg, "a bare lg must not stand in for lg + xs")
        assertEquals(
            emptyList(),
            listOf(AppSpacing.xxs, AppSpacing.xs, AppSpacing.sm, AppSpacing.md, AppSpacing.lg, AppSpacing.xl)
                .filter { it == 14.dp || it == 20.dp },
            "an off-grid card inset must not become a step, or a bare step could silently mean it",
        )
    }

    @Test
    fun `the elevation scale climbs through Material's levels`() {
        val levels =
            listOf(
                AppElevation.level0,
                AppElevation.level1,
                AppElevation.level2,
                AppElevation.level3,
                AppElevation.level4,
                AppElevation.level5,
            )

        assertEquals(listOf(0.dp, 1.dp, 3.dp, 6.dp, 8.dp, 12.dp), levels)
    }
}
