@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components.sheet

import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesPlanner
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The multi-date picker, driven the way a user drives it.
 *
 * The picker is the one place where the number of dates a user believes they
 * picked and the number that will actually be created can come apart. These
 * tests pin the three moments where that can happen: the taps that add days,
 * the counter that tells the user how many are picked, and the ceiling that
 * refuses one day too many.
 *
 * The window is narrow on purpose: the cells of a month grid are as wide as a
 * seventh of the window, and a grid that is wider than it is tall puts its
 * lower rows below the fold — where a test cannot click them, and neither can
 * a user on a short screen.
 */
@Tag("fast")
class MultiDatePickerSheetTest {

    private val today = LocalDate(2026, 10, 15)

    @Test
    fun `three tapped days are confirmed`() = runIsolatedComposeTest(width = 420, height = 1600) {
        var confirmed: Set<LocalDate>? = null
        setContent {
            MultiDatePickerSheet(
                initialDates = emptySet(),
                onConfirm = { confirmed = it },
                onDismiss = {},
                today = today,
                maxDates = TaskSeriesPlanner.MAX_DATES,
            )
        }

        clickTag(TestTags.MultiDatePicker.day("2026-10-05"))
        clickTag(TestTags.MultiDatePicker.day("2026-10-12"))
        clickTag(TestTags.MultiDatePicker.day("2026-10-19"))

        assertTextDisplayed("Выбрано: 3")

        clickTag(TestTags.MultiDatePicker.OK)

        assertEquals(
            setOf(LocalDate(2026, 10, 5), LocalDate(2026, 10, 12), LocalDate(2026, 10, 19)),
            confirmed,
            "the three days the user tapped are the three days the picker hands over",
        )
    }

    @Test
    fun `the days that start out selected are already counted`() = runIsolatedComposeTest(width = 420, height = 1600) {
        setContent {
            MultiDatePickerSheet(
                initialDates = setOf(LocalDate(2026, 10, 5)),
                onConfirm = {},
                onDismiss = {},
                today = today,
                maxDates = TaskSeriesPlanner.MAX_DATES,
            )
        }

        assertTextDisplayed("Выбрано: 1")
    }

    @Test
    fun `the ceiling refuses one day too many and says so`() = runIsolatedComposeTest(width = 420, height = 1600) {
        var confirmed: Set<LocalDate>? = null
        setContent {
            MultiDatePickerSheet(
                initialDates = emptySet(),
                onConfirm = { confirmed = it },
                onDismiss = {},
                today = today,
                maxDates = 2,
            )
        }

        clickTag(TestTags.MultiDatePicker.day("2026-10-05"))
        clickTag(TestTags.MultiDatePicker.day("2026-10-12"))
        clickTag(TestTags.MultiDatePicker.day("2026-10-19"))

        assertTextDisplayed("Выбрано: 2 — максимум 2")

        clickTag(TestTags.MultiDatePicker.OK)

        assertEquals(
            2,
            confirmed?.size,
            "a silent cap would let the user believe they picked three days",
        )
        assertTrue(
            LocalDate(2026, 10, 19) !in confirmed.orEmpty(),
            "the day that was refused is not among the chosen ones",
        )
    }

    @Test
    fun `tapping a selected day takes it back out`() = runIsolatedComposeTest(width = 420, height = 1600) {
        var confirmed: Set<LocalDate>? = null
        setContent {
            MultiDatePickerSheet(
                initialDates = emptySet(),
                onConfirm = { confirmed = it },
                onDismiss = {},
                today = today,
                maxDates = TaskSeriesPlanner.MAX_DATES,
            )
        }

        clickTag(TestTags.MultiDatePicker.day("2026-10-05"))
        clickTag(TestTags.MultiDatePicker.day("2026-10-12"))
        clickTag(TestTags.MultiDatePicker.day("2026-10-05"))

        assertTextDisplayed("Выбрано: 1")

        clickTag(TestTags.MultiDatePicker.OK)

        assertEquals(setOf(LocalDate(2026, 10, 12)), confirmed)
    }
}
