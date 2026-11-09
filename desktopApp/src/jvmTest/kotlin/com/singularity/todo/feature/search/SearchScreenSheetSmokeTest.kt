@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.presentation.nav.PreviewSearchNavigator
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagFirst
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.longClickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Open+dismiss smoke tests for the search screen's three overlays.
 *
 * Each test mounts [SearchScreenContent] — the screen without its ViewModel — and drives
 * an overlay through the control the user actually presses: the top bar's filter action,
 * the saved-searches row's save chip, and the row's long-press menu for rename. Each one
 * asserts the overlay is on screen and then that its own dismiss affordance took it away.
 * A screen whose overlay opens and cannot be closed fails here.
 *
 * A saved search is seeded into the state because the save and rename triggers live in
 * the saved-searches row, which the screen hides when nothing has been saved.
 */
@Tag("slow")
@Tag("slow:compose")
class SearchScreenSheetSmokeTest {
    private val savedSearch =
        SavedSearch(
            id = SavedSearchId.fromString("s-overdue"),
            userId = UserId("user-a"),
            name = "Overdue",
            queryString = "is:overdue",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    private fun screenState() = SearchUiState(savedSearches = persistentListOf(savedSearch))

    @Test
    fun `filter sheet opens from the top bar and dismisses`() =
        // A ModalBottomSheet is drawn in its own window, about 1080 px tall, and it
        // does not follow the test window. At the default 768 px height the sheet's
        // Apply and Cancel buttons are composed below it and cannot be pressed.
        runIsolatedComposeTest(width = 420, height = 1600) {
            setContent {
                SearchScreenContent(
                    state = screenState(),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            clickContentDescription("Filter")
            assertTagDisplayed(TestTags.SearchFilter.APPLY_BUTTON)

            clickTagFirst(TestTags.SearchFilter.CANCEL_BUTTON)
            awaitTagGone(TestTags.SearchFilter.APPLY_BUTTON)
        }

    @Test
    fun `save dialog opens from the save chip and dismisses`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state = screenState(),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            clickTag(TestTags.SavedSearches.SAVE_CHIP)
            assertTagDisplayed(TestTags.SavedSearches.SAVE_DIALOG_CONFIRM)

            clickText("Cancel")
            awaitTagGone(TestTags.SavedSearches.SAVE_DIALOG_CONFIRM)
        }

    @Test
    fun `rename dialog opens from the saved-search menu and dismisses`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreenContent(
                    state = screenState(),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            // The row offers rename only through the chip's long-press menu. A menu
            // that never opens fails on the next press rather than passing silently.
            longClickTag(TestTags.SavedSearches.chip(savedSearch.name))
            clickTagFirst(TestTags.SavedSearches.SEARCH_MENU_RENAME_BUTTON)

            assertTagDisplayed(TestTags.SavedSearches.RENAME_DIALOG_CONFIRM)

            clickText("Cancel")
            awaitTagGone(TestTags.SavedSearches.RENAME_DIALOG_CONFIRM)
        }
}
