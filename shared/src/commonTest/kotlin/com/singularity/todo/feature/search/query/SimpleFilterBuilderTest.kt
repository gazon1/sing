package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleFilterBuilderTest {

    @Test
    fun `default filter is empty and has correct defaults`() {
        val filter = simpleFilter { }
        assertTrue(filter.isEmpty)
        assertNull(filter.states)
        assertTrue(filter.priorities.isEmpty())
        assertTrue(filter.tagNames.isEmpty())
        assertNull(filter.projectName)
        assertEquals(SimpleFilter.DueCondition.NONE, filter.due)
        assertNull(filter.hasDescription)
        assertNull(filter.pinned)
        assertEquals(SortOrder.DUE, filter.sortOrder)
        assertFalse(filter.sortDescending)
    }

    @Test
    fun `state adds to states set`() {
        val filter = simpleFilter {
            state(TaskStatus.Active)
            state(TaskStatus.Completed)
        }
        assertTrue(filter.states?.contains(TaskStatus.Active) == true)
        assertTrue(filter.states?.contains(TaskStatus.Completed) == true)
    }

    @Test
    fun `priority adds to priorities set`() {
        val filter = simpleFilter {
            priority(TaskPriority.High)
            priority(TaskPriority.Urgent)
        }
        assertTrue(filter.priorities.contains(TaskPriority.High))
        assertTrue(filter.priorities.contains(TaskPriority.Urgent))
    }

    @Test
    fun `tag adds to tagNames`() {
        val filter = simpleFilter {
            tag("work")
            tag("home")
        }
        assertTrue(filter.tagNames.contains("work"))
        assertTrue(filter.tagNames.contains("home"))
        assertFalse(filter.matchAllTags)
    }

    @Test
    fun `tag with matchAll sets matchAllTags`() {
        val filter = simpleFilter {
            tag("work", matchAll = true)
        }
        assertTrue(filter.matchAllTags)
        assertTrue(filter.tagNames.contains("work"))
    }

    @Test
    fun `project sets projectName`() {
        val filter = simpleFilter { project("Plans") }
        assertEquals("Plans", filter.projectName)
    }

    @Test
    fun `project null clears projectName`() {
        val filter = simpleFilter {
            project("Plans")
            project(null)
        }
        assertNull(filter.projectName)
    }

    @Test
    fun `due sets due condition`() {
        val filter = simpleFilter {
            due(SimpleFilter.DueCondition.TODAY)
        }
        assertEquals(SimpleFilter.DueCondition.TODAY, filter.due)
    }

    @Test
    fun `customDueRange sets due and dates`() {
        val filter = simpleFilter {
            customDueRange(
                LocalDate(2026, 9, 20),
                LocalDate(2026, 9, 25),
            )
        }
        assertEquals(SimpleFilter.DueCondition.CUSTOM, filter.due)
        assertEquals(LocalDate(2026, 9, 20), filter.customDueDate)
        assertEquals(LocalDate(2026, 9, 25), filter.customDueDateEnd)
    }

    @Test
    fun `hasDescription sets hasDescription`() {
        val filter = simpleFilter { hasDescription(true) }
        assertEquals(true, filter.hasDescription)
    }

    @Test
    fun `pinned sets pinned`() {
        val filter = simpleFilter { pinned(true) }
        assertEquals(true, filter.pinned)
    }

    @Test
    fun `sortOrder sets sortOrder`() {
        val filter = simpleFilter {
            sortOrder(SortOrder.PRIORITY)
        }
        assertEquals(SortOrder.PRIORITY, filter.sortOrder)
    }

    @Test
    fun `sortDescending sets sortDescending`() {
        val filter = simpleFilter {
            sortDescending(true)
        }
        assertTrue(filter.sortDescending)
    }

    @Test
    fun `freeText sets freeText`() {
        val filter = simpleFilter {
            freeText("meeting notes")
        }
        assertEquals("meeting notes", filter.freeText)
    }

    @Test
    fun `isEmpty is false when any field is set`() {
        val filter = simpleFilter {
            tag("work")
        }
        assertFalse(filter.isEmpty)
    }

    @Test
    fun `isEmpty is true only when all defaults`() {
        val filter = simpleFilter { }
        assertTrue(filter.isEmpty)
    }
}
