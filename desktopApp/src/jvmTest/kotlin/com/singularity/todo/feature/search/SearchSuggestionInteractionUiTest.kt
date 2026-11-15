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

            assertEquals(work.id, intents.filterIsInstance<SearchIntent.OnLoadSavedSearch>().single().id)
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
        }

    @Test
    fun `outside tap dismisses suggestions without changing the query`() =
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
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
            assertEquals(emptyList<SearchIntent>(), intents)
        }

    @Test
    fun `outside tap dismisses suggestions with empty query`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state = SearchUiState(query = FieldOwnedText.empty(), savedSearches = searches),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            clickTag(TestTags.SEARCH_EMPTY_PLACEHOLDER)
            waitForIdle()

            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            awaitTagGone(TestTags.SavedSearches.chip("Overdue"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
        }

    @Test
    fun `focus loss dismisses suggestions and regaining focus restores them`() =
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
            assertEquals(emptyList<SearchIntent>(), intents)

            clickTag(TestTags.SEARCH_INPUT)
            waitForIdle()
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)
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
            assertEquals(SearchMode.Contains, intents.filterIsInstance<SearchIntent.OnModeChange>().single().mode)
            assertEquals(emptyList<SearchIntent.OnQueryChange>(), intents.filterIsInstance<SearchIntent.OnQueryChange>())
        }

    @Test
    fun `clear restores suggestions after dismissal and loading`() =
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

            clickTag(TestTags.SEARCH_MODE_CONTAINS)
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.chip("Work"))

            clickTag(TestTags.SEARCH_CLEAR_BUTTON)
            waitForIdle()

            assertEquals("", intents.filterIsInstance<SearchIntent.OnQueryChange>().last().query)
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))
        }

    @Test
    fun `opening a menu keeps suggestions visible and save remains available when dismissed`() =
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

            longClickTag(TestTags.SavedSearches.chip("Work"))
            waitForIdle()
            assertTagDisplayed(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))

            clickTag(TestTags.SEARCH_MODE_CONTAINS)
            waitForIdle()
            awaitTagGone(TestTags.SavedSearches.chip("Work"))
            assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)

            clickTag(TestTags.SavedSearches.SAVE_CHIP)
            waitForIdle()
            assertTagDisplayed(TestTags.SavedSearches.SAVE_DIALOG_CONFIRM)
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
            assertEquals(emptyList<SearchIntent.OnQueryChange>(), intents.filterIsInstance<SearchIntent.OnQueryChange>())
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

            assertEquals("tag:work x", intents.filterIsInstance<SearchIntent.OnQueryChange>().last().query)
        }
}
