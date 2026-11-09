package com.singularity.todo.feature.search

import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * The four states of the search screen.
 *
 * "Nothing typed yet", "the search is still running" and "the search found
 * nothing" are different products: the first invites the user to type, the
 * second reports that the question is being asked, and the third reports the
 * answer. The Maestro flows assert the first and the third by different test
 * tags (`search_empty_placeholder` and `search_empty_results`), so a screen
 * that shows one where the other belongs contradicts the flows even when it
 * looks reasonable.
 *
 * The decision is a pure function so it can be asserted here; the composable
 * that draws it has no Compose test harness in this module.
 */
@Tag("fast")
class SearchContentTest {
    @Test
    fun `an empty query shows the idle prompt`() {
        assertEquals(SearchContent.IdlePrompt, searchContent("", NO_RESULTS, false))
    }

    @Test
    fun `a blank query shows the idle prompt even when results are still around`() {
        // The results of the previous query must not linger under a blank one —
        // the screen would then show a list the user did not ask for.
        assertEquals(SearchContent.IdlePrompt, searchContent("   ", ONE_RESULT, false))
    }

    @Test
    fun `a query that matched nothing shows the no-results state`() {
        assertEquals(SearchContent.NoResults, searchContent("zzzznomatchzzzz999999", NO_RESULTS, false))
    }

    @Test
    fun `a query that matched nothing does not show the idle prompt`() {
        // Telling the user to type a query they have already typed is the defect
        // this state exists to prevent.
        val content = searchContent("zzzznomatchzzzz999999", NO_RESULTS, false)
        assertIs<SearchContent.NoResults>(content)
    }

    @Test
    fun `a query that matched a task shows the results`() {
        assertEquals(SearchContent.Results, searchContent("milk", ONE_RESULT, false))
    }

    @Test
    fun `a query that has not run yet shows the searching state`() {
        // The first letter of a query is not an answer to a search that has not
        // run: reporting "no results" here would answer a question nobody asked.
        assertEquals(SearchContent.Searching, searchContent("m", NO_RESULTS, true))
    }

    @Test
    fun `a pending search does not show the no-results state`() {
        val content = searchContent("m", NO_RESULTS, true)
        assertIs<SearchContent.Searching>(content)
    }

    @Test
    fun `a pending search does not show the results of the previous query`() {
        // The results that are on the screen are the answer to the *previous*
        // query. They are not the answer to the query being typed.
        assertEquals(SearchContent.Searching, searchContent("milk", ONE_RESULT, true))
    }

    @Test
    fun `a blank query shows the idle prompt even while a search is in flight`() {
        assertEquals(SearchContent.IdlePrompt, searchContent("   ", ONE_RESULT, true))
    }

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)

        val NO_RESULTS = SearchResults(emptyList(), emptyList(), emptyList(), emptyList())

        val ONE_RESULT =
            SearchResults(
                listOf(
                    IndexedRecord(
                        record =
                            Task(
                                id = TaskId("t-milk"),
                                title = "Buy milk",
                                createdAt = NOW,
                                updatedAt = NOW,
                                userId = TestUsers.DEFAULT,
                            ),
                        ranges = persistentListOf(),
                    ),
                ),
                emptyList(),
                emptyList(),
                emptyList(),
            )
    }
}
