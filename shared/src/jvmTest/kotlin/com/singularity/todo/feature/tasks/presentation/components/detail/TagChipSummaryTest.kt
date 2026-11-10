package com.singularity.todo.feature.tasks.presentation.components.detail

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tags chip is one control, so its label has to say both what the tags are and that
 * there are more of them. #2736 asks for an explicit overflow summary, not a truncated
 * name that leaves the count to the picker.
 */
class TagChipSummaryTest {
    @Test
    fun `no tags asks for tags`() {
        assertEquals("Add tags", tagChipSummary(persistentListOf(), 0))
    }

    @Test
    fun `a single tag is named`() {
        assertEquals("Work", tagChipSummary(persistentListOf("Work"), 1))
    }

    @Test
    fun `several names lead with the count`() {
        assertEquals("2 tags · Work, Home", tagChipSummary(persistentListOf("Work", "Home"), 2))
        assertEquals(
            "5 tags · Work, Home",
            tagChipSummary(persistentListOf("Work", "Home", "Errands", "Health", "Finance"), 5),
        )
    }

    @Test
    fun `a long first name cannot truncate the count away`() {
        assertEquals(
            "2 tags · extremely-long-tag-name-that-keeps-going, another-long-tag-name",
            tagChipSummary(
                persistentListOf("extremely-long-tag-name-that-keeps-going", "another-long-tag-name"),
                2,
            ),
        )
    }

    @Test
    fun `an id-only collection falls back to its count`() {
        assertEquals("5 tags", tagChipSummary(persistentListOf(), 5))
        assertEquals("1 tag", tagChipSummary(persistentListOf(), 1))
    }
}
