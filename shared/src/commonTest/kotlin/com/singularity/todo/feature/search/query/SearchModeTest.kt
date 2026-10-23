package com.singularity.todo.feature.search.query

import com.singularity.todo.core.search.FreeTextRule
import com.singularity.todo.core.search.TextMatchPlan
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.TestUsers
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The mode, and what it can ask.
 *
 * The mode is the escape-hatch of the word-prefix rule: the word mode finds a
 * word by its beginning, the contains mode finds the term wherever it occurs.
 * The mode also says what the index can do — and the answer for the contains
 * mode is always "nothing", because the index stores words and cannot say
 * whether a term occurs in the middle of one.
 *
 * See `docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`.
 */
@Tag("fast")
class SearchModeTest {

    @Test
    fun `the word mode finds a word by its beginning`() {
        assertTrue(SearchMode.Word.matches("architecture", "arch"))
        assertFalse(SearchMode.Word.matches("airport", "port"))
    }

    @Test
    fun `the contains mode finds the term wherever it occurs`() {
        assertTrue(SearchMode.Contains.matches("airport", "port"))
        assertTrue(SearchMode.Contains.matches("Airport transfer", "port"))
        assertFalse(SearchMode.Contains.matches("architecture", "port"))
    }

    @Test
    fun `the contains mode ignores the case`() {
        assertTrue(SearchMode.Contains.matches("Airport transfer", "PORT"))
        assertTrue(SearchMode.Word.matches("Architecture", "arch"))
    }

    @Test
    fun `a task is matched by its title and its description`() {
        val task = task(title = "Trip", description = "Airport transfer")

        assertTrue(SearchMode.Contains.matches(task, "port"))
        assertTrue(SearchMode.Word.matches(task, "air"))
        assertFalse(SearchMode.Word.matches(task, "port"))
    }

    @Test
    fun `the word mode narrows the rule and the index answers it`() {
        val plan = SearchMode.Word.plan(rule("port"))

        assertIs<TextMatchPlan.Narrowed>(plan)
    }

    @Test
    fun `the contains mode never narrows the rule`() {
        val plan = SearchMode.Contains.plan(rule("port"))

        assertEquals(TextMatchPlan.BroadScan, plan)
        assertEquals(
            TextMatchPlan.BroadScan,
            SearchMode.Contains.plan(rule("port", negated = true)),
            "the index has no answer for a term that occurs anywhere — not even for a negated one",
        )
    }

    private fun rule(text: String, negated: Boolean = false): FreeTextRule =
        FreeTextRule(terms = listOf(FreeTextRule.Term(text = text, negated = negated)))

    private fun task(title: String, description: String?): Task = Task(
        id = TaskId("t-1"),
        title = title,
        description = description,
        createdAt = NOW,
        updatedAt = NOW,
        userId = TestUsers.DEFAULT,
    )

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)
    }
}
