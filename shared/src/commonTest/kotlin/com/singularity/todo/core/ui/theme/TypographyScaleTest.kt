package com.singularity.todo.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The user's text scale reaches every slot of the type scale, not only the ones a screen reads.
 *
 * [createTypography] names its slots one by one, and `Typography`'s constructor quietly substitutes
 * Material 3's built-in style for a slot that is not named — an unscaled one. The list is therefore
 * only as trustworthy as this class: the two tables below walk all thirty slots the theme exposes,
 * so a slot that stops being scaled renders at its unscaled baseline and fails the "grows" tests.
 * That includes the fifteen Emphasized slots the app has never adopted, which is where the omission
 * this change exists for actually was.
 *
 * The sizes of the fifteen slots the app renders are pinned here as numbers, for the opposite
 * reason. [createTypography] writes them out, so a wrong copy — or a dependency bump that moved a
 * size — would otherwise reach the screens with nothing to notice it. The Emphasized slots are
 * checked against Material 3's own defaults, because for them the property worth pinning is that
 * they scale, not that they equal a number the app never chose.
 *
 * **Sensitivity.** Deleting one slot from [createTypography] turns its "grows" test red: the slot
 * then renders at its baseline size instead of `baseline × 1.25`, which is the same as 57 sp instead
 * of 71.25 sp. Changing a baseline number in [createTypography] turns the "unchanged at scale 1"
 * test red.
 */
@Tag("fast")
class TypographyScaleTest {
    /**
     * Material 3's own type scale: what the slots the app has not adopted are compared against.
     *
     * Built once, because it is a plain value holder and rebuilding it per test would say nothing
     * extra.
     */
    private val materialBaseline = Typography()

    /** One slot the app renders: how to read it, and the Material 3 baseline it is written out at. */
    private class Slot(
        val name: String,
        val fontSize: TextUnit,
        val lineHeight: TextUnit,
        val read: (Typography) -> TextStyle,
    )

    /** The fifteen slots the app renders, at the sizes it has always rendered them. */
    private val renderedSlots =
        listOf(
            Slot("displayLarge", 57.sp, 64.sp, Typography::displayLarge),
            Slot("displayMedium", 45.sp, 56.sp, Typography::displayMedium),
            Slot("displaySmall", 36.sp, 48.sp, Typography::displaySmall),
            Slot("headlineLarge", 32.sp, 40.sp, Typography::headlineLarge),
            Slot("headlineMedium", 28.sp, 36.sp, Typography::headlineMedium),
            Slot("headlineSmall", 24.sp, 32.sp, Typography::headlineSmall),
            Slot("titleLarge", 22.sp, 28.sp, Typography::titleLarge),
            Slot("titleMedium", 16.sp, 24.sp, Typography::titleMedium),
            Slot("titleSmall", 14.sp, 20.sp, Typography::titleSmall),
            Slot("bodyLarge", 16.sp, 24.sp, Typography::bodyLarge),
            Slot("bodyMedium", 14.sp, 20.sp, Typography::bodyMedium),
            Slot("bodySmall", 12.sp, 16.sp, Typography::bodySmall),
            Slot("labelLarge", 14.sp, 20.sp, Typography::labelLarge),
            Slot("labelMedium", 12.sp, 16.sp, Typography::labelMedium),
            Slot("labelSmall", 11.sp, 14.sp, Typography::labelSmall),
        )

    /** The fifteen Emphasized slots the app has never adopted, paired with how to read them. */
    private val unadoptedSlots =
        listOf(
            "displayLargeEmphasized" to Typography::displayLargeEmphasized,
            "displayMediumEmphasized" to Typography::displayMediumEmphasized,
            "displaySmallEmphasized" to Typography::displaySmallEmphasized,
            "headlineLargeEmphasized" to Typography::headlineLargeEmphasized,
            "headlineMediumEmphasized" to Typography::headlineMediumEmphasized,
            "headlineSmallEmphasized" to Typography::headlineSmallEmphasized,
            "titleLargeEmphasized" to Typography::titleLargeEmphasized,
            "titleMediumEmphasized" to Typography::titleMediumEmphasized,
            "titleSmallEmphasized" to Typography::titleSmallEmphasized,
            "bodyLargeEmphasized" to Typography::bodyLargeEmphasized,
            "bodyMediumEmphasized" to Typography::bodyMediumEmphasized,
            "bodySmallEmphasized" to Typography::bodySmallEmphasized,
            "labelLargeEmphasized" to Typography::labelLargeEmphasized,
            "labelMediumEmphasized" to Typography::labelMediumEmphasized,
            "labelSmallEmphasized" to Typography::labelSmallEmphasized,
        )

    @Test
    fun `the slots the app renders are unchanged at scale 1`() {
        val typography = createTypography(1f)

        renderedSlots.forEach { slot ->
            val style = slot.read(typography)
            assertEquals(slot.fontSize, style.fontSize, "${slot.name} font size at scale 1")
            assertEquals(slot.lineHeight, style.lineHeight, "${slot.name} line height at scale 1")
        }
    }

    @Test
    fun `every slot the app renders grows with the text scale`() {
        val typography = createTypography(1.25f)

        renderedSlots.forEach { slot ->
            val style = slot.read(typography)
            assertEquals(slot.fontSize * 1.25f, style.fontSize, "${slot.name} font size at scale 1.25")
            assertEquals(slot.lineHeight * 1.25f, style.lineHeight, "${slot.name} line height at scale 1.25")
        }
    }

    @Test
    fun `the slots the app has not adopted follow the text scale too`() {
        val atScaleOne = createTypography(1f)
        val scaled = createTypography(1.25f)

        unadoptedSlots.forEach { (name, read) ->
            // The app does not restyle these; at the baseline it must reproduce Material exactly.
            assertEquals(read(materialBaseline), read(atScaleOne), "$name at scale 1")
            // And it must scale them, which is what a screen adopting one relies on.
            assertEquals(
                read(materialBaseline).fontSize * 1.25f,
                read(scaled).fontSize,
                "$name font size at scale 1.25",
            )
            assertEquals(
                read(materialBaseline).lineHeight * 1.25f,
                read(scaled).lineHeight,
                "$name line height at scale 1.25",
            )
        }
    }

    @Test
    fun `the slots the brief names take the scale`() {
        val typography = createTypography(1.25f)

        // A style the app renders and the factory names.
        assertEquals(17.5.sp, typography.bodyMedium.fontSize)
        // A display style no screen reads today.
        assertEquals(71.25.sp, typography.displayLarge.fontSize)
        // A style the app has never adopted: scaled by the policy, not by being noticed.
        assertEquals(
            materialBaseline.displayMediumEmphasized.fontSize * 1.25f,
            typography.displayMediumEmphasized.fontSize,
        )
    }
}
