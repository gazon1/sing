package com.singularity.todo.feature.plans.domain

import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the granularity and its horizon.
 */
@Tag("fast")
class PlansGranularityTest {

    @Test
    fun `the horizons are thirty days, twelve weeks and twelve months`() {
        assertEquals(30, PlansGranularity.Day.periodCount)
        assertEquals(12, PlansGranularity.Week.periodCount)
        assertEquals(12, PlansGranularity.Month.periodCount)
    }

    @Test
    fun `a profile that never chose a granularity gets days`() {
        assertEquals(PlansGranularity.Day, PlansGranularity.Default)
        assertEquals(PlansGranularity.Day, PlansGranularity.fromStorage(null))
    }

    @Test
    fun `a stored granularity reads back`() {
        assertEquals(PlansGranularity.Week, PlansGranularity.fromStorage("Week"))
        assertEquals(PlansGranularity.Month, PlansGranularity.fromStorage("Month"))
    }

    @Test
    fun `a stored value this build does not know falls back to days`() {
        assertEquals(PlansGranularity.Day, PlansGranularity.fromStorage("Year"))
        assertEquals(PlansGranularity.Day, PlansGranularity.fromStorage("day"))
        assertEquals(PlansGranularity.Day, PlansGranularity.fromStorage(""))
    }
}
