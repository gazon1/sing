package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.search.query.Condition.And
import com.singularity.todo.feature.search.query.Condition.Due
import com.singularity.todo.feature.search.query.Condition.HasAllTags
import com.singularity.todo.feature.search.query.Condition.HasPriority
import com.singularity.todo.feature.search.query.Condition.HasStatus
import com.singularity.todo.feature.search.query.Condition.HasTag
import com.singularity.todo.feature.search.query.Condition.HasText
import com.singularity.todo.feature.search.query.Condition.InProject
import com.singularity.todo.feature.search.query.Condition.IsArchived
import com.singularity.todo.feature.search.query.Condition.IsPinned
import com.singularity.todo.feature.search.query.Condition.Not
import com.singularity.todo.feature.search.query.Condition.Or
import com.singularity.todo.feature.search.query.QueryInterval
import com.singularity.todo.feature.search.query.Relation.GT
import com.singularity.todo.feature.search.query.Relation.LE
import com.singularity.todo.feature.search.query.SortOrder
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SingularityQueryParserTest {

    private fun parse(input: String): Query = SingularityQueryParser(input).parse()

    // ─── Bare text ─────────────────────────────────────────────────────────

    @Test
    fun `empty string returns empty query`() {
        assertTrue(parse("").isEmpty)
        assertTrue(parse("   ").isEmpty)
    }

    @Test
    fun `single bare word becomes HasText`() {
        val q = parse("meeting")
        assertIs<HasText>(q.condition)
        assertEquals("meeting", q.condition.text)
    }

    @Test
    fun `quoted phrase becomes HasText`() {
        val q = parse("\"quarterly review\"")
        assertIs<HasText>(q.condition)
        assertEquals("quarterly review", q.condition.text)
    }

    // ─── Status ─────────────────────────────────────────────────────────────

    @Test
    fun `state active`() {
        val q = parse("state:active")
        assertIs<HasStatus>(q.condition)
        assertEquals(TaskStatus.Active, q.condition.status)
    }

    @Test
    fun `state completed`() {
        val q = parse("state:completed")
        assertIs<HasStatus>(q.condition)
        assertEquals(TaskStatus.Completed, q.condition.status)
    }

    @Test
    fun `state all`() {
        val q = parse("state:all")
        assertIs<HasStatus>(q.condition)
        assertEquals(TaskStatus.All, q.condition.status)
    }

    @Test
    fun `state case insensitive`() {
        val q = parse("STATE:DONE")
        assertIs<HasStatus>(q.condition)
        assertEquals(TaskStatus.Completed, q.condition.status)
    }

    @Test
    fun `negated state`() {
        val q = parse("-state:completed")
        assertIs<Not>(q.condition)
        val inner = q.condition.inner as HasStatus
        assertEquals(TaskStatus.Completed, inner.status)
    }

    // ─── Priority ──────────────────────────────────────────────────────────

    @Test
    fun `priority values`() {
        for ((str, expected) in listOf(
            "priority:none" to TaskPriority.None,
            "priority:low" to TaskPriority.Low,
            "priority:medium" to TaskPriority.Medium,
            "priority:high" to TaskPriority.High,
            "priority:urgent" to TaskPriority.Urgent,
        )) {
            val q = parse(str)
            assertIs<HasPriority>(q.condition, str)
            assertEquals(expected, q.condition.priority, str)
        }
    }

    @Test
    fun `negated priority`() {
        val q = parse("-priority:high")
        assertIs<Not>(q.condition)
        assertEquals(TaskPriority.High, (q.condition.inner as HasPriority).priority)
    }

    // ─── Tags ──────────────────────────────────────────────────────────────

    @Test
    fun `tag single`() {
        val q = parse("tag:work")
        assertIs<HasTag>(q.condition)
        assertEquals("work", q.condition.tagName)
    }

    @Test
    fun `negated tag`() {
        val q = parse("-tag:work")
        assertIs<Not>(q.condition)
        assertEquals("work", (q.condition.inner as HasTag).tagName)
    }

    @Test
    fun `tags multi AND`() {
        val q = parse("tags:work,urgent,home")
        assertIs<HasAllTags>(q.condition)
        assertEquals(setOf("work", "urgent", "home"), q.condition.tagNames)
    }

    @Test
    fun `negated tags multi`() {
        val q = parse("-tags:work,urgent")
        assertIs<Not>(q.condition)
        assertEquals(setOf("work", "urgent"), (q.condition.inner as HasAllTags).tagNames)
    }

    // ─── Project ────────────────────────────────────────────────────────────

    @Test
    fun `project by name`() {
        val q = parse("project:Plans")
        assertIs<InProject>(q.condition)
        assertEquals("Plans", q.condition.name)
    }

    // ─── Due date ───────────────────────────────────────────────────────────

    @Test
    fun `due today`() {
        val q = parse("due:today")
        assertIs<Due>(q.condition)
        // TODAY = NOW = 0 days; default relation for "due:" is LE ("within today")
        assertEquals(QueryInterval(0), q.condition.interval)
        assertEquals(LE, q.condition.relation)
    }

    @Test
    fun `due relative numeric`() {
        val q = parse("due:3d")
        assertIs<Due>(q.condition)
        assertEquals(QueryInterval(3), q.condition.interval)
        assertEquals(LE, q.condition.relation)
    }

    @Test
    fun `due past numeric`() {
        val q = parse("due:-1w")
        assertIs<Due>(q.condition)
        assertEquals(QueryInterval(-7), q.condition.interval)
        assertEquals(LE, q.condition.relation)
    }

    @Test
    fun `due with GT relation`() {
        val q = parse("due:>3d")
        assertIs<Due>(q.condition)
        assertEquals(QueryInterval(3), q.condition.interval)
        assertEquals(GT, q.condition.relation)
    }

    @Test
    fun `due tomorrow alias`() {
        val q = parse("due:tomorrow")
        assertIs<Due>(q.condition)
        assertEquals(QueryInterval(1), q.condition.interval)
    }

    @Test
    fun `due yesterday alias`() {
        val q = parse("due:yesterday")
        assertIs<Due>(q.condition)
        assertEquals(QueryInterval(-1), q.condition.interval)
    }

    // ─── Scheduled ─────────────────────────────────────────────────────────

    @Test
    fun `scheduled today`() {
        val q = parse("scheduled:today")
        assertIs<com.singularity.todo.feature.search.query.Condition.Scheduled>(q.condition)
        assertEquals(QueryInterval(0), q.condition.interval)
    }

    @Test
    fun `scheduled relative`() {
        val q = parse("scheduled:-2d")
        assertIs<com.singularity.todo.feature.search.query.Condition.Scheduled>(q.condition)
        assertEquals(QueryInterval(-2), q.condition.interval)
    }

    // ─── Has description ───────────────────────────────────────────────────

    @Test
    fun `has description`() {
        val q = parse("has:description")
        assertIs<com.singularity.todo.feature.search.query.Condition.HasDescription>(q.condition)
    }

    // ─── Boolean combinators ────────────────────────────────────────────────

    @Test
    fun `implicit AND between two conditions`() {
        val q = parse("priority:high tag:work")
        assertIs<And>(q.condition)
        assertEquals(2, q.condition.parts.size)
    }

    @Test
    fun `explicit AND`() {
        val q = parse("priority:high AND tag:work")
        assertIs<And>(q.condition)
    }

    @Test
    fun `OR has lower precedence than AND`() {
        val q = parse("a AND b OR c")
        assertIs<Or>(q.condition)
        // Parsed as: OR(AND(a, b), c)
        assertEquals(2, q.condition.parts.size)
        assertIs<And>(q.condition.parts[0])
        assertIs<HasText>(q.condition.parts[1])
    }

    @Test
    fun `OR without parens`() {
        val q = parse("a OR b AND c")
        assertIs<Or>(q.condition)
        // Without parens, AND binds tighter: OR(a, AND(b, c))
        assertEquals(2, q.condition.parts.size)
    }

    @Test
    fun `NOT wraps next atom`() {
        val q = parse("NOT tag:archived")
        assertIs<Not>(q.condition)
        assertIs<HasTag>(q.condition.inner)
    }

    @Test
    fun `complex nested`() {
        val q = parse("(priority:high OR priority:urgent) AND tag:work")
        assertIs<And>(q.condition)
        assertIs<Or>(q.condition.parts[0])
        assertIs<HasTag>(q.condition.parts[1])
    }

    // ─── Special flags ─────────────────────────────────────────────────────

    @Test
    fun `pinned`() {
        val q = parse("pinned")
        assertIs<IsPinned>(q.condition)
    }

    @Test
    fun `archived`() {
        val q = parse("archived")
        assertIs<IsArchived>(q.condition)
    }

    // ─── Sort order ─────────────────────────────────────────────────────────

    @Test
    fun `sort by priority`() {
        val q = parse("priority:high sort:priority")
        assertEquals(SortOrder.PRIORITY, q.sortOrder)
    }

    @Test
    fun `sort by due descending`() {
        val q = parse("sort:due desc")
        assertEquals(SortOrder.DUE, q.sortOrder)
        assertTrue(q.sortDescending)
    }

    @Test
    fun `sort by title`() {
        val q = parse("tag:work sort:title")
        assertEquals(SortOrder.TITLE, q.sortOrder)
    }

    @Test
    fun `default sort is DUE`() {
        val q = parse("meeting")
        assertEquals(SortOrder.DUE, q.sortOrder)
    }

    // ─── Limit ─────────────────────────────────────────────────────────────

    @Test
    fun `limit option`() {
        val q = parse("limit:20")
        assertEquals(20, q.options.limit)
    }

    @Test
    fun `limit coerced to 1000`() {
        val q = parse("limit:5000")
        assertEquals(1000, q.options.limit)
    }

    @Test
    fun `default limit is 50`() {
        assertEquals(50, parse("meeting").options.limit)
    }

    // ─── Combined queries ───────────────────────────────────────────────────

    @Test
    fun `realistic query`() {
        val q = parse("priority:high tag:work project:Plans due:today sort:priority")
        assertIs<And>(q.condition)
        assertEquals(SortOrder.PRIORITY, q.sortOrder)
        assertEquals(50, q.options.limit)
    }

    @Test
    fun `query with negative tag and pinned`() {
        val q = parse("-tag:archived pinned")
        assertIs<And>(q.condition)
        assertIs<Not>(q.condition.parts[0])
        assertIs<IsPinned>(q.condition.parts[1])
    }
}
