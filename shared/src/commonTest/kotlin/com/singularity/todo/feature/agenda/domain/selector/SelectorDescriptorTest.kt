package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.model.Selector
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class SelectorDescriptorTest {
    /**
     * The work-block label is built from the user's own work-schedule minutes,
     * and the agenda preset moves each end by a minute. Neither end can be a
     * time of day, so the label must not print one.
     */
    @Test
    fun `the work block label clamps a bound that is not a time of day`() {
        assertEquals("Work block: 00:00–12:00", Selector.TimeOfDay(-1, 720).typeDescription)
        assertEquals("Work block: 09:00–24:00", Selector.TimeOfDay(540, 1440).typeDescription)
    }
}
