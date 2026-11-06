package com.singularity.todo.core.ui.theme

import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's typography states a line height for every style.
 *
 * Without an explicit `lineHeight` the spacing between two lines comes from the
 * font's own metrics, so it neither follows the app's font-size setting nor
 * keeps the rhythm of the Material 3 type scale. These tests pin the two
 * properties that matter: the baseline at scale 1, and the way the line height
 * follows the same factor as the font size.
 */
@Tag("fast")
class TypographyLineHeightTest {
    @Test
    fun `every style sets the material 3 line height`() {
        val typography = createTypography(1f)

        val lineHeights =
            listOf(
                typography.displayLarge.lineHeight,
                typography.displayMedium.lineHeight,
                typography.displaySmall.lineHeight,
                typography.headlineLarge.lineHeight,
                typography.headlineMedium.lineHeight,
                typography.headlineSmall.lineHeight,
                typography.titleLarge.lineHeight,
                typography.titleMedium.lineHeight,
                typography.titleSmall.lineHeight,
                typography.bodyLarge.lineHeight,
                typography.bodyMedium.lineHeight,
                typography.bodySmall.lineHeight,
                typography.labelLarge.lineHeight,
                typography.labelMedium.lineHeight,
                typography.labelSmall.lineHeight,
            )

        val expected =
            listOf(
                64.sp,
                56.sp,
                48.sp,
                40.sp,
                36.sp,
                32.sp,
                28.sp,
                24.sp,
                20.sp,
                24.sp,
                20.sp,
                16.sp,
                20.sp,
                16.sp,
                14.sp,
            )

        assertEquals(expected, lineHeights)
    }

    @Test
    fun `the line height scales with the font size scale`() {
        val typography = createTypography(1.5f)

        assertEquals(96.sp, typography.displayLarge.lineHeight)
        assertEquals(54.sp, typography.headlineMedium.lineHeight)
        assertEquals(30.sp, typography.bodyMedium.lineHeight)
        assertEquals(21.sp, typography.labelSmall.lineHeight)
    }
}
