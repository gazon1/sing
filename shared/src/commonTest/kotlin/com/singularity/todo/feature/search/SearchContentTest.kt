package com.singularity.todo.feature.search

import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.TestUsers
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * The three states of the search screen.
 *
 * "Nothing typed yet" and "the search found nothing" are different products: the
 * first invites the user to type, the second reports the answer. The Maestro
 * flows assert them by different test tags (`search_empty_placeholder` and
 * `search_empty_results`), so a screen that shows one where the other belongs
 * contradicts the flows even when it looks reasonable.
 *
 * The decision is a pure function so it can be asserted here; the composable
 * that draws it has no Compose test harness in this module.
 */
@Tag("fast")
class SearchContentTest {

    @Test
    fun `an empty query shows the idle prompt`() {
        assertEquals(SearchContent.IdlePrompt, searchContent("", NO_RESULTS))
    }

    @Test
    fun `a blank query shows the idle prompt even when results are still around`() {
        // The results of the previous query must not linger under a blank one —
        // the screen would then show a list the user did not ask for.
        assertEquals(SearchContent.IdlePrompt, searchContent("   ", ONE_RESULT))
    }

    @Test
    fun `a query that matched nothing shows the no-results state`() {
        assertEquals(SearchContent.NoResults, searchContent("zzzznomatchzzzz999999", NO_RESULTS))
    }

    @Test
    fun `a query that matched nothing does not show the idle prompt`() {
        // Telling the user to type a query they have already typed is the defect
        // this state exists to prevent.
        val content = searchContent("zzzznomatchzzzz999999", NO_RESULTS)
        assertIs<SearchContent.NoResults>(content)
    }

    @Test
    fun `a query that matched a task shows the results`() {
        assertEquals(SearchContent.Results, searchContent("milk", ONE_RESULT))
    }

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)

        val NO_RESULTS = SearchResults(emptyList(), emptyList(), emptyList(), emptyList())

        val ONE_RESULT = SearchResults(
            listOf(
                IndexedRecord(
                    record = Task(
                        id = TaskId("t-milk"),
                        title = "Buy milk",
                        createdAt = NOW,
                        updatedAt = NOW,
                        userId = TestUsers.DEFAULT,
                    ),
                    ranges = emptyList(),
                ),
            ),
            emptyList(),
            emptyList(),
            emptyList(),
        )
    }
}
