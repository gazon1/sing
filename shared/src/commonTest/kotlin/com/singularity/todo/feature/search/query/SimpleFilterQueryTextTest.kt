package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for writing a filter back into the query text language.
 *
 * The contract is a round trip: the text that comes out says what the filter
 * said, so a saved search that carries it loads the query that was running.
 */
@Tag("fast")
class SimpleFilterQueryTextTest {
    // ─── The text ───────────────────────────────────────────────────────────

    @Test
    fun `the free text comes first and the constraints follow it`() {
        val filter =
            SimpleFilter(
                priorities = setOf(TaskPriority.High),
                freeTextTerms = listOf("milk"),
            )
        assertEquals("milk priority:high", filter.toQueryText())
    }

    @Test
    fun `every constraint has its own word`() {
        val filter =
            SimpleFilter(
                states = setOf(TaskStatus.Active),
                priorities = setOf(TaskPriority.Urgent),
                tagNames = setOf("work"),
                projectName = "Plans",
                due = SimpleFilter.DueCondition.TODAY,
                hasDescription = true,
                pinned = true,
                freeTextTerms = listOf("milk"),
            )
        assertEquals(
            "milk state:active priority:urgent tag:work project:Plans due:=today has:description pinned",
            filter.toQueryText(),
        )
    }

    @Test
    fun `a due date keeps the relation the filter has`() {
        // `due:today` means "on or before that day"; the filter means "on that
        // day", and the text has to say so.
        assertEquals("due:=today", SimpleFilter(due = SimpleFilter.DueCondition.TODAY).toQueryText())
        assertEquals("due:=tomorrow", SimpleFilter(due = SimpleFilter.DueCondition.TOMORROW).toQueryText())
        assertEquals("due:=7d", SimpleFilter(due = SimpleFilter.DueCondition.THIS_WEEK).toQueryText())
        assertEquals("due:=-1d", SimpleFilter(due = SimpleFilter.DueCondition.OVERDUE).toQueryText())
    }

    @Test
    fun `no due constraint says nothing about dates`() {
        assertEquals("milk", SimpleFilter(freeTextTerms = listOf("milk")).toQueryText())
    }

    @Test
    fun `all the tags are one condition when the filter wants all of them`() {
        val filter = SimpleFilter(tagNames = setOf("work", "home"), matchAllTags = true)
        assertEquals("tags:work,home", filter.toQueryText())
    }

    @Test
    fun `one tag is a tag of its own`() {
        assertEquals("tag:work", SimpleFilter(tagNames = setOf("work")).toQueryText())
    }

    @Test
    fun `the sort is written the way the language reads it`() {
        assertEquals("sort:priority", SimpleFilter(sortOrder = SortOrder.PRIORITY).toQueryText())
        assertEquals(
            "sort:title desc",
            SimpleFilter(sortOrder = SortOrder.TITLE, sortDescending = true).toQueryText(),
        )
    }

    @Test
    fun `the default sort is not written`() {
        assertEquals("milk", SimpleFilter(freeTextTerms = listOf("milk")).toQueryText())
    }

    // ─── The terms ──────────────────────────────────────────────────────────

    @Test
    fun `a term the language would read as something else is quoted`() {
        val filter = SimpleFilter(freeTextTerms = listOf("pinned"))
        assertEquals("\"pinned\"", filter.toQueryText())
    }

    @Test
    fun `a term of several words is quoted`() {
        val filter = SimpleFilter(freeTextTerms = listOf("milk bread"))
        assertEquals("\"milk bread\"", filter.toQueryText())
    }

    @Test
    fun `a term that carries a prefix is quoted`() {
        val filter = SimpleFilter(freeTextTerms = listOf("due:3d"))
        assertEquals("\"due:3d\"", filter.toQueryText())
    }

    @Test
    fun `each term keeps its own words`() {
        val filter = SimpleFilter(freeTextTerms = listOf("milk", "bread"))
        assertEquals("milk bread", filter.toQueryText())
    }

    // ─── What the language cannot say ───────────────────────────────────────

    @Test
    fun `a custom date range has no text`() {
        assertNull(SimpleFilter(due = SimpleFilter.DueCondition.CUSTOM).toQueryText())
    }

    @Test
    fun `a name the language cannot spell has no text`() {
        assertNull(SimpleFilter(tagNames = setOf("work stuff")).toQueryText())
        assertNull(SimpleFilter(projectName = "Home Stuff").toQueryText())
    }

    @Test
    fun `an empty term has no text`() {
        assertNull(SimpleFilter(freeTextTerms = listOf("")).toQueryText())
    }

    // ─── The round trip ─────────────────────────────────────────────────────

    @Test
    fun `the text reads back as the query the filter is`() {
        val filter =
            SimpleFilter(
                priorities = setOf(TaskPriority.High),
                freeTextTerms = listOf("milk"),
            )
        val text = filter.toQueryText()
        assertEquals(
            Condition.And(
                listOf(
                    Condition.HasText("milk"),
                    Condition.HasPriority(TaskPriority.High),
                ),
            ),
            SingularityQueryParser(text!!).parse().condition,
        )
    }

    @Test
    fun `a quoted term reads back as the word it was`() {
        val text = SimpleFilter(freeTextTerms = listOf("pinned")).toQueryText()
        assertEquals(
            Condition.HasText("pinned"),
            SingularityQueryParser(text!!).parse().condition,
        )
    }

    @Test
    fun `a due date reads back with the relation it was written with`() {
        val text = SimpleFilter(due = SimpleFilter.DueCondition.TODAY).toQueryText()
        assertEquals(
            Condition.Due(QueryInterval.NOW, Relation.EQ),
            SingularityQueryParser(text!!).parse().condition,
        )
    }
}
