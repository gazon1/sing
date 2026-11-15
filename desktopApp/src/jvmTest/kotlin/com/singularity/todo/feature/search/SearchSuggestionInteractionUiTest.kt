@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.search.IndexedRecord
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.presentation.nav.PreviewSearchNavigator
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.longClickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

@Tag("slow")
@Tag("slow:compose")
class SearchSuggestionInteractionUiTest {
    private val now = Instant.fromEpochMilliseconds(0)

    private fun savedSearch(
        id: String,
        name: String,
        query: String,
    ) = SavedSearch(
        id = SavedSearchId.fromString(id),
        userId = UserId("user-a"),
        name = name,
        queryString = query,
        createdAt = now,
        updatedAt = now,
    )

    private val work = savedSearch("s-work", "Work", "tag:work")
    private val overdue = savedSearch("s-overdue", "Overdue", "is:overdue")
    private val searches = persistentListOf(overdue, work)

    @Test
    fun `selecting a suggestion loads it and dismisses suggestions`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("wo"),
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))

            clickText("Work")
            waitForIdle()

            val load = intents.filterIsInstance<SearchIntent.OnLoadSavedSearch>().single()
            assertEquals(work.id, load.id)
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
        }

    @Test
    fun `outside tap on empty results dismisses suggestions without changing the query`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("zzzz"),
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.NO_MATCH)

            clickTag(TestTags.SEARCH_EMPTY_RESULTS)
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.NO_MATCH)
            assertTagDisplayed(TestTags.SEARCH_EMPTY_RESULTS)
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
            assertEquals(emptyList<SearchIntent>(), intents)
        }

    @Test
    fun `moving focus to the filter action dismisses suggestions without changing the query`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))

            clickContentDescription("Filter")
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SearchFilter.APPLY_BUTTON)
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
            assertEquals(emptyList<SearchIntent>(), intents)
        }

    @Test
    fun `clear returns to the empty-query suggestion state`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))

            clickTag(TestTags.SEARCH_CLEAR_BUTTON)
            waitForIdle()

            val change = intents.filterIsInstance<SearchIntent.OnQueryChange>().last()
            assertEquals("", change.query)
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))
        }

    @Test
    fun `editing a loaded saved search dispatches query change`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("tag:work"),
                            activeSavedSearchId = work.id,
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagTextEquals(TestTags.SEARCH_INPUT, "tag:work")

            typeIntoTag(TestTags.SEARCH_INPUT, " x")
            waitForIdle()

            val changes = intents.filterIsInstance<SearchIntent.OnQueryChange>()
            assertIs<SearchIntent.OnQueryChange>(changes.last())
            assertEquals("tag:work x", changes.last().query)
        }

    @Test
    fun `switching mode dismisses suggestions without changing the query`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                            mode = SearchMode.Word,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))

            clickTag(TestTags.SEARCH_MODE_CONTAINS)
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
            val modeChange = intents.filterIsInstance<SearchIntent.OnModeChange>().single()
            assertEquals(SearchMode.Contains, modeChange.mode)
            assertEquals(
                emptyList<SearchIntent.OnQueryChange>(),
                intents.filterIsInstance<SearchIntent.OnQueryChange>(),
            )
        }

    @Test
    fun `tapping a result dismisses suggestions without changing the query`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            val task =
                Task(
                    id = TaskId("t-1"),
                    title = "Buy milk",
                    createdAt = now,
                    updatedAt = now,
                    userId = UserId("user-a"),
                )
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                            results =
                                SearchResults(
                                    tasks = persistentListOf(IndexedRecord(task)),
                                    notes = persistentListOf(),
                                    projects = persistentListOf(),
                                    tags = persistentListOf(),
                                ),
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))

            clickTag(TestTags.taskItem("Buy milk"))
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
            assertTagNotExists(TestTags.SavedSearches.chip("Overdue"))
            assertEquals(
                emptyList<SearchIntent.OnQueryChange>(),
                intents.filterIsInstance<SearchIntent.OnQueryChange>(),
            )
        }

    @Test
    fun `opening a suggestion menu keeps suggestions visible`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded("work"),
                            savedSearches = searches,
                        ),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))

            longClickTag(TestTags.SavedSearches.chip("Work"))
            waitForIdle()

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
        }

    @Test
    fun `clear after loading a saved search restores all suggestions`() =
        runIsolatedComposeTest {
            val intents = mutableListOf<SearchIntent>()
            setContent {
                SearchScreenContent(
                    state =
                        SearchUiState(
                            query = FieldOwnedText.empty().seeded(work.queryString),
                            activeSavedSearchId = work.id,
                            savedSearches = searches,
                        ),
                    onIntent = { intents += it },
                    navigator = PreviewSearchNavigator(),
                )
            }

            clickTag(TestTags.SEARCH_CLEAR_BUTTON)
            waitForIdle()

            assertEquals("", intents.filterIsInstance<SearchIntent.OnQueryChange>().last().query)
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))
        }
}
