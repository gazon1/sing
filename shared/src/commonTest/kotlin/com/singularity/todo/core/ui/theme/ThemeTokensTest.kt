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
