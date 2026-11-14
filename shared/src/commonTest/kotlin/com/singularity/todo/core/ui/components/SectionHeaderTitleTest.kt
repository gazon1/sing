package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule the section headers count by.
 *
 * A header that says "Checklist (0/0)" reports a count over nothing: the reader
 * learns nothing and the number reads like a fault. An empty section therefore
 * says only its name, and the count appears when there is something to count.
 */
@Tag("fast")
class SectionHeaderTitleTest {
    @Test
    fun anEmptySectionSaysOnlyItsName() {
        assertEquals("Checklist", sectionTitle("Checklist", total = 0))
    }

    @Test
    fun aSectionWithItemsSaysHowManyThereAre() {
        assertEquals("Attachments (3)", sectionTitle("Attachments", total = 3))
    }

    @Test
    fun aSectionWithADoneCountSaysHowManyAreDone() {
        assertEquals("Checklist (2/5)", sectionTitle("Checklist", total = 5, done = 2))
    }

    @Test
    fun aSectionWithNothingDoneAndNothingTotalSaysOnlyItsName() {
        // "Checklist (0/0)" is the count over nothing this rule exists to avoid.
        assertEquals("Checklist", sectionTitle("Checklist", total = 0, done = 0))
    }
}
