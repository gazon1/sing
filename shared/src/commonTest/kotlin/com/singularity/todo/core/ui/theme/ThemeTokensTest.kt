package com.singularity.todo.core.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The theme's token scales, pinned by value.
 *
 * The task feature used to carry two spacing scales and two answers for a card's
 * corner, and nothing failed when they disagreed — `xl` was 24 dp in the editor
 * and 20 dp in the list. A scale is the promise that one name means one number,
 * so the promise is asserted here instead of being left to the next reader of
 * two files to notice.
 *
 * The scale alone is not enough: what a call site *does* with it can still drift.
 * The cards once padded 14 dp vertically, and swapping that for a bare `lg` is a
 * 4 dp change that compiles, passes every other test, and is invisible until
 * somebody compares screenshots. The off-grid values the call sites must keep are
 * therefore pinned here as the compositions the call sites actually write.
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

        assertEquals(steps.distinct(), steps, "two names in the scale carry the same value")
        assertEquals(steps.sorted(), steps, "the scale is not ascending")
        assertEquals(listOf(2.dp, 4.dp, 8.dp, 12.dp, 16.dp, 24.dp), steps)
    }

    @Test
    fun `a task card's corner is the theme's large slot, not a second number`() {
        assertEquals(CornerSize(16.dp), AppShapes.large.topStart)
        assertEquals(CornerSize(12.dp), AppShapes.medium.topStart)
    }

    @Test
    fun `the cards keep the off-grid insets their old tokens carried`() {
        // TaskAttributeCard, LinkedBacklinksCard and both LogbookSection cards padded
        // 14 dp vertically before the task feature's two token sets were folded into one
        // scale. 14 dp is not a step, so those call sites compose it as md + xs, and the
        // composition is pinned here: a bare `lg` at any of them still compiles and still
        // passes the rest of this file, while growing every card by 4 dp.
        assertEquals(14.dp, AppSpacing.md + AppSpacing.xs, "a card's vertical inset must stay 14 dp")
        assertEquals(16.dp, AppSpacing.lg, "a card's horizontal inset must stay 16 dp")

        // The onboarding spotlight card's 20 dp inset is off-grid for the same reason: no
        // call site ever read `TaskListSpacing.Xl`, so the scale dropped the step and the
        // one card that wants it composes it.
        assertEquals(20.dp, AppSpacing.lg + AppSpacing.xs, "the spotlight card's inset must stay 20 dp")
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

        assertEquals(levels.sorted(), levels, "the elevation scale is not ascending")
        assertEquals(listOf(0.dp, 1.dp, 3.dp, 6.dp, 8.dp, 12.dp), levels)
    }
}
