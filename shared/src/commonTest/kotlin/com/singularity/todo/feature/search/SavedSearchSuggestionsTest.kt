package com.singularity.todo.feature.search

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The rule that decides which saved searches are offered beside the field.
 *
 * The screen reads the list it already observes — nothing is asked of the
 * database per keystroke — so the whole behaviour is this function, and it is
 * asserted here.
 */
@Tag("fast")
class SavedSearchSuggestionsTest {
    @Test
    fun `a blank query offers every saved search`() {
        assertEquals(listOf("Overdue", "Due this week", "Work"), matching(""))
        assertEquals(listOf("Overdue", "Due this week", "Work"), matching("   "))
    }

    @Test
    fun `no saved searches offer nothing`() {
        assertTrue(savedSearchSuggestions(persistentListOf(), "work").isEmpty())
    }

    @Test
    fun `the query filters by saved-search name, case-insensitively`() {
        assertEquals(listOf("Work"), matching("work"))
        assertEquals(listOf("Work"), matching("WORK"))
    }

    @Test
    fun `the query filters by the stored query string`() {
        assertEquals(listOf("Overdue"), matching("overdue"))
        assertEquals(listOf("Due this week"), matching("7d"))
    }

    @Test
    fun `a query that matches neither name nor stored query offers nothing`() {
        assertEquals(emptyList(), matching("zzzznomatch"))
    }

    @Test
    fun `the filter keeps the list's own order`() {
        // Every stored query carries the condition separator, so all three match
        // and they come back in the list's order, not the match's.
        assertEquals(listOf("Overdue", "Due this week", "Work"), matching(":"))
    }

    private fun matching(query: String) = savedSearchSuggestions(ALL, query).map { it.name }

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)

        fun search(
            id: String,
            name: String,
            query: String,
        ) = SavedSearch(
            id = SavedSearchId(id),
            userId = UserId("u1"),
            name = name,
            queryString = query,
            createdAt = NOW,
            updatedAt = NOW,
        )

        val OVERDUE = search("s1", "Overdue", "is:overdue")
        val WEEK = search("s2", "Due this week", "due:7d")
        val WORK = search("s3", "Work", "tag:work")

        val ALL = listOf(OVERDUE, WEEK, WORK).toImmutableList()
    }
}
