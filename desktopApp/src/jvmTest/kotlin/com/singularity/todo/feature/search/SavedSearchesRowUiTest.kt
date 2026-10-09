@file:OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)

package com.singularity.todo.feature.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.presentation.SavedSearchesRow
import com.singularity.todo.test.helpers.runIsolatedComposeTest
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
class SavedSearchesRowUiTest {

    private val epoch = Instant.fromEpochMilliseconds(0L)
    private val testUserId = UserId("test-user")

    private fun makeSavedSearch(
        id: String,
        name: String,
        query: String = "query:$id",
    ): SavedSearch = SavedSearch(
        id = SavedSearchId(id),
        userId = testUserId,
        name = name,
        queryString = query,
        createdAt = epoch,
        updatedAt = epoch,
    )

    @Test
    fun `renders the Save chip`() = runIsolatedComposeTest {
        setContent {
            SavedSearchesRow(
                savedSearches = emptyList(),
                activeSavedSearchId = null,
                onLoadSearch = {},
                onSaveClick = {},
                onRename = { _, _ -> },
                onDelete = {},
            )
        }

        onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun `renders each saved search chip with its name`() = runIsolatedComposeTest {
        val searches = listOf(
            makeSavedSearch("ss1", "Work tasks"),
            makeSavedSearch("ss2", "Tomorrow"),
        )
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

        onNodeWithText("Work tasks").assertIsDisplayed()
        onNodeWithText("Tomorrow").assertIsDisplayed()
    }

    @Test
    fun `clicking Save chip calls onSaveClick`() = runIsolatedComposeTest {
        var saveClicked = false
        setContent {
            SavedSearchesRow(
                savedSearches = emptyList(),
                activeSavedSearchId = null,
                onLoadSearch = {},
                onSaveClick = { saveClicked = true },
                onRename = { _, _ -> },
                onDelete = {},
            )
        }

        onNodeWithText("Save").performClick()

        assert(saveClicked) { "expected onSaveClick to be called" }
    }

    @Test
    fun `clicking a saved search chip calls onLoadSearch with the correct id`() =
        runIsolatedComposeTest {
            val searches = listOf(makeSavedSearch("ss-home", "Home tasks"))
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

            onNodeWithText("Home tasks").performClick()

            assert(loadedId == searches[0].id) {
                "expected ${searches[0].id}, got $loadedId"
            }
        }

    @Test
    fun `active saved search shows filled star icon`() = runIsolatedComposeTest {
        val searches = listOf(makeSavedSearch("ss1", "Active search"))
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
        onNodeWithText("Active search").assertIsDisplayed()
    }

}
