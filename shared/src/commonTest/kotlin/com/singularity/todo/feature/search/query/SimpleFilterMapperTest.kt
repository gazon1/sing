package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("fast")
class SimpleFilterMapperTest {

    private val mapper = SimpleFilterMapper()

    // ─── fromQuery ───────────────────────────────────────────────────────────

    @Test
    fun `empty query maps to empty filter with defaults`() {
        val result = mapper.fromQuery(Query.EMPTY)
        assertTrue(result.isSuccess)
        val filter = result.getOrThrow()
        assertTrue(filter.isEmpty)
        assertEquals(SortOrder.DUE, filter.sortOrder)
        assertEquals(false, filter.sortDescending)
    }

    @Test
    fun `HasText becomes freeText`() {
        val query = Query(condition = Condition.HasText("meeting"))
        val result = mapper.fromQuery(query)
        assertEquals("meeting", result.getOrNull()?.freeText)
    }

    @Test
    fun `HasStatus maps to state`() {
        val query = Query(condition = Condition.HasStatus(TaskStatus.Active))
        val result = mapper.fromQuery(query)
        assertTrue(result.getOrNull()?.states?.contains(TaskStatus.Active) == true)
    }

    @Test
    fun `HasPriority maps to priority`() {
        val query = Query(condition = Condition.HasPriority(TaskPriority.High))
        val result = mapper.fromQuery(query)
        assertTrue(result.getOrNull()?.priorities?.contains(TaskPriority.High) == true)
    }

    @Test
    fun `HasTag maps to tagName`() {
        val query = Query(condition = Condition.HasTag("work"))
        val result = mapper.fromQuery(query)
        assertTrue(result.getOrNull()?.tagNames?.contains("work") == true)
    }

    @Test
    fun `HasAllTags maps to matchAllTags`() {
        val query = Query(condition = Condition.HasAllTags(setOf("work", "home")))
        val result = mapper.fromQuery(query)
        val filter = result.getOrNull()
        assertTrue(filter?.tagNames?.containsAll(setOf("work", "home")) == true)
        assertTrue(filter?.matchAllTags == true)
    }

    @Test
    fun `InProject maps to projectName`() {
        val query = Query(condition = Condition.InProject("Plans"))
        val result = mapper.fromQuery(query)
        assertEquals("Plans", result.getOrNull()?.projectName)
    }

    @Test
    fun `Due TODAY maps to DueCondition TODAY`() {
        val query = Query(condition = Condition.Due(QueryInterval.NOW, Relation.EQ))
        val result = mapper.fromQuery(query)
        assertEquals(SimpleFilter.DueCondition.TODAY, result.getOrNull()?.due)
    }

    @Test
    fun `Due TOMORROW maps to DueCondition TOMORROW`() {
        val query = Query(condition = Condition.Due(QueryInterval.TOMORROW, Relation.EQ))
        val result = mapper.fromQuery(query)
        assertEquals(SimpleFilter.DueCondition.TOMORROW, result.getOrNull()?.due)
    }

    @Test
    fun `Due THIS_WEEK (7 days) maps to DueCondition THIS_WEEK`() {
        val query = Query(condition = Condition.Due(QueryInterval(7), Relation.EQ))
        val result = mapper.fromQuery(query)
        assertEquals(SimpleFilter.DueCondition.THIS_WEEK, result.getOrNull()?.due)
    }

    @Test
    fun `Due OVERDUE (-1 days) maps to DueCondition OVERDUE`() {
        val query = Query(condition = Condition.Due(QueryInterval(-1), Relation.EQ))
        val result = mapper.fromQuery(query)
        assertEquals(SimpleFilter.DueCondition.OVERDUE, result.getOrNull()?.due)
    }

    @Test
    fun `Due NONE maps to DueCondition NONE`() {
        val query = Query(condition = Condition.Due(QueryInterval.NONE, Relation.EQ))
        val result = mapper.fromQuery(query)
        assertEquals(SimpleFilter.DueCondition.NONE, result.getOrNull()?.due)
    }

    @Test
    fun `Due with GT relation degrades to TODAY`() {
        val query = Query(condition = Condition.Due(QueryInterval(3), Relation.GT))
        val result = mapper.fromQuery(query)
        assertTrue(result.isSuccess)
        assertEquals(SimpleFilter.DueCondition.TODAY, result.getOrNull()?.due)
    }

    @Test
    fun `Scheduled throws UnsupportedSimpleFilterException`() {
        val query = Query(condition = Condition.Scheduled(QueryInterval(3), Relation.GT))
        val result = mapper.fromQuery(query)
        assertTrue(result.isFailure)
    }

    @Test
    fun `IsArchived throws UnsupportedSimpleFilterException`() {
        val query = Query(condition = Condition.IsArchived)
        val result = mapper.fromQuery(query)
        assertTrue(result.isFailure)
    }

    @Test
    fun `Not throws UnsupportedSimpleFilterException`() {
        val query = Query(condition = Condition.Not(Condition.HasTag("work")))
        val result = mapper.fromQuery(query)
        assertTrue(result.isFailure)
    }

    @Test
    fun `Or throws UnsupportedSimpleFilterException`() {
        val query = Query(
            condition = Condition.Or(
                listOf(
                    Condition.HasTag("work"),
                    Condition.HasTag("home"),
                ),
            ),
        )
        val result = mapper.fromQuery(query)
        assertTrue(result.isFailure)
    }

    @Test
    fun `HasDescription maps to hasDescription true`() {
        val query = Query(condition = Condition.HasDescription)
        val result = mapper.fromQuery(query)
        assertEquals(true, result.getOrNull()?.hasDescription)
    }

    @Test
    fun `IsPinned maps to pinned true`() {
        val query = Query(condition = Condition.IsPinned)
        val result = mapper.fromQuery(query)
        assertEquals(true, result.getOrNull()?.pinned)
    }

    @Test
    fun `sortOrder and sortDescending are passed through`() {
        val query = Query(
            condition = Condition.HasText("test"),
            sortOrder = SortOrder.PRIORITY,
            sortDescending = true,
        )
        val result = mapper.fromQuery(query)
        val filter = result.getOrNull()!!
        assertEquals(SortOrder.PRIORITY, filter.sortOrder)
        assertEquals(true, filter.sortDescending)
    }

    @Test
    fun `complex query with unsupported condition throws`() {
        val query = Query(
            condition = Condition.And(
                listOf(
                    Condition.HasTag("work"),
                    Condition.IsArchived,
                ),
            ),
        )
        val result = mapper.fromQuery(query)
        assertTrue(result.isFailure)
    }

    // ─── toQuery ────────────────────────────────────────────────────────────

    @Test
    fun `empty filter maps to Query with no condition`() {
        val filter = SimpleFilter()
        val result = mapper.toQuery(filter)
        assertTrue(result.isSuccess)
        // Empty filter → Query with no condition
        val query = result.getOrThrow()
        assertNull(query.condition)
    }

    @Test
    fun `filter with state maps to HasStatus`() {
        val filter = SimpleFilter(states = setOf(TaskStatus.Active))
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.HasStatus>(query.condition)
    }

    @Test
    fun `filter with priority maps to HasPriority`() {
        val filter = SimpleFilter(priorities = setOf(TaskPriority.High))
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.HasPriority>(query.condition)
    }

    @Test
    fun `filter with single tag maps to HasTag`() {
        val filter = simpleFilter { tag("work") }
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.HasTag>(query.condition)
        assertEquals("work", (query.condition as Condition.HasTag).tagName)
    }

    @Test
    fun `filter with multiple tags and matchAll maps to HasAllTags`() {
        val filter = simpleFilter {
            tag("work", matchAll = true)
            tag("home", matchAll = true)
        }
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.HasAllTags>(query.condition)
    }

    @Test
    fun `filter with project maps to InProject`() {
        val filter = simpleFilter { project("Plans") }
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.InProject>(query.condition)
        assertEquals("Plans", (query.condition as Condition.InProject).name)
    }

    @Test
    fun `filter with TODAY due maps to Due with NOW interval`() {
        val filter = simpleFilter { due(SimpleFilter.DueCondition.TODAY) }
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.Due>(query.condition)
        val due = query.condition as Condition.Due
        assertEquals(QueryInterval.NOW, due.interval)
        assertEquals(Relation.EQ, due.relation)
    }

    @Test
    fun `filter with CUSTOM due throws UnsupportedSimpleFilterException`() {
        val filter = simpleFilter {
            due(SimpleFilter.DueCondition.CUSTOM)
        }
        val result = mapper.toQuery(filter)
        assertTrue(result.isFailure)
    }

    @Test
    fun `filter with hasDescription maps to HasDescription`() {
        val filter = simpleFilter { hasDescription(true) }
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.HasDescription>(query.condition)
    }

    @Test
    fun `filter with pinned maps to IsPinned`() {
        val filter = simpleFilter { pinned(true) }
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.IsPinned>(query.condition)
    }

    @Test
    fun `filter with freeText maps to HasText`() {
        val filter = simpleFilter { freeText("meeting") }
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.HasText>(query.condition)
        assertEquals("meeting", (query.condition as Condition.HasText).text)
    }

    @Test
    fun `filter preserves sortOrder and sortDescending`() {
        val filter = SimpleFilter(
            states = setOf(TaskStatus.Active),
            sortOrder = SortOrder.PRIORITY,
            sortDescending = true,
        )
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertEquals(SortOrder.PRIORITY, query.sortOrder)
        assertEquals(true, query.sortDescending)
    }

    @Test
    fun `filter with multiple conditions wraps in AND`() {
        val filter = simpleFilter {
            state(TaskStatus.Active)
            priority(TaskPriority.High)
            tag("work")
        }
        val result = mapper.toQuery(filter)
        val query = result.getOrNull()!!
        assertIs<Condition.And>(query.condition)
        assertEquals(3, query.condition.parts.size)
    }
}
