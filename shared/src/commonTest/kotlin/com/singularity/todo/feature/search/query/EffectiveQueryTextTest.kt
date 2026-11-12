package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the text a saved search carries.
 *
 * The rule is one sentence: the user's text is kept while it still says what
 * runs, and the running filter is written out when it does not.
 */
@Tag("fast")
class EffectiveQueryTextTest {
    private val parse: (String) -> Query = { SingularityQueryParser(it).parse() }

    @Test
    fun `the text is kept while it still says what runs`() {
        val text = "milk priority:high"
        assertEquals(
            text,
            effectiveQueryText(
                raw = text,
                executed = parse(text),
                activeFilter = null,
                parse = parse,
            ),
        )
    }

    @Test
    fun `a typed query is kept whole`() {
        // The text language says more than a filter can hold; a saved search
        // that rewrote it would no longer be the search that was asked for.
        val text = "due:3d limit:10"
        assertEquals(
            text,
            effectiveQueryText(
                raw = text,
                executed = parse(text),
                activeFilter = null,
                parse = parse,
            ),
        )
    }

    @Test
    fun `the running filter is written out when the text no longer says it`() {
        val filter = SimpleFilter(freeTextTerms = listOf("milk"))
        assertEquals(
            "milk",
            effectiveQueryText(
                raw = "milk priority:high",
                executed = parse("milk"),
                activeFilter = filter,
                parse = parse,
            ),
        )
    }

    @Test
    fun `the filter is written with its own constraints`() {
        val filter =
            SimpleFilter(
                priorities = setOf(TaskPriority.High),
                freeTextTerms = listOf("milk"),
            )
        // The screen runs the filter; the field still reads the free text alone.
        assertEquals(
            "milk priority:high",
            effectiveQueryText(
                raw = "milk",
                executed = parse("milk priority:high"),
                activeFilter = filter,
                parse = parse,
            ),
        )
    }

    @Test
    fun `a filter the language cannot say keeps the text`() {
        // Half a query would say something the screen is not doing, so the text
        // stands as it is.
        val text = "milk"
        assertEquals(
            text,
            effectiveQueryText(
                raw = text,
                executed = parse("milk"),
                activeFilter = SimpleFilter(due = SimpleFilter.DueCondition.CUSTOM),
                parse = parse,
            ),
        )
    }

    @Test
    fun `a query that runs nothing keeps the text`() {
        val text = "milk"
        assertEquals(
            text,
            effectiveQueryText(
                raw = text,
                executed = null,
                activeFilter = SimpleFilter(freeTextTerms = listOf("milk")),
                parse = parse,
            ),
        )
    }
}
