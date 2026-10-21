package com.singularity.todo.feature.search.query

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The records that carry the word in their title come first.
 *
 * FTS4 has no `bm25`, so the index cannot tell a title match from a body
 * match. The rule can, and it is what the search uses.
 */
@Tag("fast")
class TitleMatchRankingTest {

    private data class Record(val title: String, val body: String)

    @Test
    fun `the record that carries the word in its title comes first`() {
        val records = listOf(
            Record(title = "Shopping", body = "buy architecture books"),
            Record(title = "Architecture", body = "the house"),
        )

        val ranked = titleMatchesFirst(records) { record -> record.title.startsWith("arch") }

        assertEquals(
            listOf("Architecture", "Shopping"),
            ranked.map { it.title },
        )
    }

    @Test
    fun `the order inside each group is the order the user asked for`() {
        // The records arrive sorted by their due date. The ranking groups them,
        // and the order inside the group is the one they came in with.
        val records = listOf(
            Record(title = "Later title match", body = ""),
            Record(title = "Earlier title match", body = ""),
            Record(title = "Body match", body = "architecture"),
        )

        val ranked = titleMatchesFirst(records) { record -> record.title.contains("title match") }

        assertEquals(
            listOf("Later title match", "Earlier title match", "Body match"),
            ranked.map { it.title },
            "the group comes first, and the order inside it is untouched",
        )
    }

    @Test
    fun `a query with no free text changes nothing`() {
        val records = listOf(Record(title = "a", body = "b"), Record(title = "c", body = "d"))

        val ranked = titleMatchesFirst(records) { record -> record.title.isEmpty() }

        assertEquals(records, ranked)
    }
}
