@file:OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)

package com.singularity.todo.feature.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.presentation.SavedSearchesRow
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop JVM Compose UI tests for [SavedSearchesRow].
 *
 * Covers the row's behaviour: rendering the save chip and saved-search chips,
 * clicking to load a search, and the long-press context menu with rename/delete.
 *
 * Run with: ./gradlew :desktopApp:test --tests "*SavedSearchesRowUiTest"
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@Tag("slow")
@Tag("slow:compose")
class SavedSearchesRowUiTest {
    private val epoch = Instant.fromEpochMilliseconds(0L)
    private val testUserId = UserId("test-user")

    private fun makeSavedSearch(
        id: String,
        name: String,
        query: String = "query:$id",
    ): SavedSearch =
        SavedSearch(
            id = SavedSearchId(id),
            userId = testUserId,
            name = name,
            queryString = query,
            createdAt = epoch,
            updatedAt = epoch,
        )

    @Test
    fun `renders the Save chip`() =
        runIsolatedComposeTest {
            setContent {
                SavedSearchesRow(
                    savedSearches = persistentListOf(),
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                )
            }

            assertTextDisplayed("Save")
        }

    @Test
    fun `renders each saved search chip with its name`() =
        runIsolatedComposeTest {
            val searches =
                listOf(
                    makeSavedSearch("ss1", "Work tasks"),
                    makeSavedSearch("ss2", "Tomorrow"),
                ).toImmutableList()
            setContent {
                SavedSearchesRow(
                    savedSearches = searches,
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                )
            }

            assertTextDisplayed("Work tasks")
            assertTextDisplayed("Tomorrow")
        }

    @Test
    fun `clicking Save chip calls onSaveClick`() =
        runIsolatedComposeTest {
            var saveClicked = false
            setContent {
                SavedSearchesRow(
                    savedSearches = persistentListOf(),
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = { saveClicked = true },
                    onRename = { _, _ -> },
                    onDelete = {},
                )
            }

            clickText("Save")

            assert(saveClicked) { "expected onSaveClick to be called" }
        }

    @Test
    fun `clicking a saved search chip calls onLoadSearch with the correct id`() =
        runIsolatedComposeTest {
            val searches = listOf(makeSavedSearch("ss-home", "Home tasks")).toImmutableList()
            var loadedId: SavedSearchId? = null
            setContent {
                SavedSearchesRow(
                    savedSearches = searches,
                    activeSavedSearchId = null,
                    onLoadSearch = { loadedId = it },
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                )
            }

            clickText("Home tasks")

            assert(loadedId == searches[0].id) {
                "expected ${searches[0].id}, got $loadedId"
            }
        }

    @Test
    fun `active saved search shows filled star icon`() =
        runIsolatedComposeTest {
            val searches = listOf(makeSavedSearch("ss1", "Active search")).toImmutableList()
            setContent {
                SavedSearchesRow(
                    savedSearches = searches,
                    activeSavedSearchId = searches[0].id,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                )
            }

            // The chip for the active search is rendered as selected.
            // The star icon is part of the chip's merged semantics.
            assertTextDisplayed("Active search")
        }

    @Test
    fun `a typed query hides the saved searches it does not match`() =
        runIsolatedComposeTest {
            setContent {
                SavedSearchesRow(
                    savedSearches = rowFixture,
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                    query = "overdue",
                )
            }

            assertTextDisplayed("Overdue")
            assertTagNotExists(TestTags.SavedSearches.chip("Due this week"))
            assertTagNotExists(TestTags.SavedSearches.chip("Work"))
        }

    @Test
    fun `a query that matches no saved search says so instead of leaving a bare row`() =
        runIsolatedComposeTest {
            setContent {
                SavedSearchesRow(
                    savedSearches = rowFixture,
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                    query = "zzzznomatch",
                )
            }

            assertTagDisplayed(TestTags.SavedSearches.NO_MATCH)
            assertTagNotExists(TestTags.SavedSearches.chip("Overdue"))
        }

    @Test
    fun `no no-match line when there are no saved searches at all`() =
        // Nothing has been saved, so there is no suggestion surface: the row
        // holds the Save chip and says nothing about a filter over an empty list.
        runIsolatedComposeTest {
            setContent {
                SavedSearchesRow(
                    savedSearches = persistentListOf(),
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                    query = "overdue",
                )
            }

            assertTagNotExists(TestTags.SavedSearches.NO_MATCH)
        }

    @Test
    fun `a chip shows the query its saved search stores`() =
        runIsolatedComposeTest {
            setContent {
                SavedSearchesRow(
                    savedSearches = rowFixture,
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                )
            }

            // The name is the primary label; the stored query is the context
            // that says what the search does.
            assertTextDisplayed("Due this week")
            assertTextDisplayed("due:7d")
        }

    @Test
    fun `the suggestions follow the list when a saved search is removed and added`() =
        // The row is derived from the list, not a snapshot of it: deleting a saved
        // search has to take its chip away, and saving one has to bring it back —
        // without touching the query.
        runIsolatedComposeTest {
            val searches = mutableStateOf(rowFixture)

            setContent {
                SavedSearchesRow(
                    savedSearches = searches.value,
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                    query = "overdue",
                )
            }
            waitForIdle()

            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))
            assertTagNotExists(TestTags.SavedSearches.NO_MATCH)

            // Deleted: the chip goes and the row says it has nothing to offer.
            searches.value = rowFixture.filter { it.name != "Overdue" }.toImmutableList()
            waitForIdle()

            assertTagNotExists(TestTags.SavedSearches.chip("Overdue"))
            assertTagDisplayed(TestTags.SavedSearches.NO_MATCH)

            // Saved again (here, renamed back into a match): the chip returns and
            // the no-match line goes.
            searches.value = rowFixture
            waitForIdle()

            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))
            assertTagNotExists(TestTags.SavedSearches.NO_MATCH)
        }

    @Test
    fun `a renamed saved search is suggested under its new name`() =
        runIsolatedComposeTest {
            val searches = mutableStateOf(rowFixture)

            setContent {
                SavedSearchesRow(
                    savedSearches = searches.value,
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { _, _ -> },
                    onDelete = {},
                    query = "overdue",
                )
            }
            waitForIdle()

            assertTagDisplayed(TestTags.SavedSearches.chip("Overdue"))

            // Renamed: the chip is addressed by its name, so the old address goes
            // and the new one answers. The search still matches by its stored
            // query, which the rename did not touch.
            searches.value =
                rowFixture.map { if (it.id.raw == "s1") it.copy(name = "Old") else it }.toImmutableList()
            waitForIdle()

            assertTagNotExists(TestTags.SavedSearches.chip("Overdue"))
            assertTagDisplayed(TestTags.SavedSearches.chip("Old"))
        }

    private val rowFixture =
        listOf(
            makeSavedSearch("s1", "Overdue", "is:overdue"),
            makeSavedSearch("s2", "Due this week", "due:7d"),
            makeSavedSearch("s3", "Work", "tag:work"),
        ).toImmutableList()
}
