package com.singularity.todo.feature.settings

import com.singularity.todo.feature.settings.screens.TimeSliderUnit
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two units a work-schedule row can be measured in, and what each one means
 * for the slider it drives and the picker it opens.
 *
 * These are the conversions the greeting rows got wrong: an hour was handed to a
 * slider that ranged over minutes, and the picker wrote minutes back into an
 * hour-shaped setting. The screen test proves which unit each row is wired to;
 * this one proves the arithmetic each unit implies.
 */
@Tag("fast")
class WorkScheduleTimeUnitTest {
    @Test
    fun `an hour row covers one day with a step per hour`() {
        assertEquals(23f, TimeSliderUnit.Hours.sliderMax)
        assertEquals(22, TimeSliderUnit.Hours.sliderSteps)
    }

    @Test
    fun `a minute row keeps the fifteen minute grid`() {
        assertEquals(1439f, TimeSliderUnit.Minutes.sliderMax)
        assertEquals((1439 / 15) - 1, TimeSliderUnit.Minutes.sliderSteps)
    }

    @Test
    fun `the picker opens on the stored value in the row's own unit`() {
        // 12 as an hour is noon, not twelve past midnight.
        assertEquals(12 to 0, TimeSliderUnit.Hours.pickerSeed(12))
        // 570 as minutes is half past nine.
        assertEquals(9 to 30, TimeSliderUnit.Minutes.pickerSeed(570))
    }

    @Test
    fun `the picker writes back what the row stores`() {
        assertEquals(14, TimeSliderUnit.Hours.pickerValue(14, 30))
        assertEquals(870, TimeSliderUnit.Minutes.pickerValue(14, 30))
    }

    @Test
    fun `the field shows each unit in its own shape`() {
        assertEquals("13:00", TimeSliderUnit.Hours.format(13))
        assertEquals("09:30", TimeSliderUnit.Minutes.format(570))
    }
}
