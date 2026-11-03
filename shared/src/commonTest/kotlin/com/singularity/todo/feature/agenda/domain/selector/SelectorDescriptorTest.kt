package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.model.Selector
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class SelectorDescriptorTest {
    /**
     * The work-block label is built from the user's own work-schedule minutes,
     * and the agenda preset moves each end by a minute. A day has 1440 minutes,
     * numbered 0..1439, so −1 and 1440 are not minutes of the day and the label
     * must not print them as times — "0:-1" and "24:00" are not clocks.
     */
    @Test
    fun `the work block label clamps a bound that is not a time of day`() {
        assertEquals("Work block: 00:00–12:00", Selector.TimeOfDay(-1, 720).typeDescription)
        assertEquals("Work block: 09:00–23:59", Selector.TimeOfDay(540, 1440).typeDescription)
    }

    /** An end that falls before its start would read backwards, so it is lifted to the start. */
    @Test
    fun `the work block label never reads backwards`() {
        assertEquals("Work block: 09:00–09:00", Selector.TimeOfDay(540, -1).typeDescription)
    }
}
