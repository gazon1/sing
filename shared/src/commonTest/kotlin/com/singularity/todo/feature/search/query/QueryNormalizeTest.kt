package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.search.query.Condition.And
import com.singularity.todo.feature.search.query.Condition.HasPriority
import com.singularity.todo.feature.search.query.Condition.HasStatus
import com.singularity.todo.feature.search.query.Condition.HasTag
import com.singularity.todo.feature.search.query.SortOrder
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Tag("fast")
class QueryNormalizeTest {

    private fun parse(input: String): Query = SingularityQueryParser(input).parse()

    @Test
    fun `multiple HasPriority in AND flattened`() {
        val q = parse("priority:high priority:urgent")
        val cond = q.condition as And
        assertEquals(2, cond.parts.size)
        assertIs<HasPriority>(cond.parts[0])
        assertIs<HasPriority>(cond.parts[1])
    }

    @Test
    fun `single condition has no AND wrapper`() {
        val q = parse("priority:high")
        assertIs<HasPriority>(q.condition)
    }

    @Test
    fun `same priority repeated is not auto-deduplicated`() {
        val q = parse("priority:high priority:high")
        val cond = q.condition as And
        assertEquals(2, cond.parts.size)
    }

    @Test
    fun `mixed conditions in AND preserved`() {
        val q = parse("priority:high tag:work state:active")
        val cond = q.condition as And
        assertEquals(3, cond.parts.size)
        assertIs<HasPriority>(cond.parts[0])
        assertIs<HasTag>(cond.parts[1])
        assertIs<HasStatus>(cond.parts[2])
    }

    @Test
    fun `AND with empty parts produces empty AND`() {
        val q = parse("AND")
        val cond = q.condition as And
        assertTrue(cond.parts.isEmpty())
    }

    @Test
    fun `sort order flags separate from condition`() {
        val q = parse("priority:high sort:priority desc limit:50")
        assertEquals(SortOrder.PRIORITY, q.sortOrder)
        assertTrue(q.sortDescending)
        assertEquals(50, q.options.limit)
        assertIs<HasPriority>(q.condition)
    }

    @Test
    fun `negated status in AND`() {
        val q = parse("-state:completed tag:work")
        val cond = q.condition as And
        assertEquals(2, cond.parts.size)
    }
}
