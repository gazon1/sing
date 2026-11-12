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
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.scrollableBoundsOfTag
import com.singularity.todo.test.helpers.windowBoundsOfTag
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
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
    fun `filter sheet opens from the top bar and its actions can be reached`() =
        // A ModalBottomSheet is drawn in its own window and does not follow the
        // test window. The sheet is bounded by that window — its body is
        // measured against it and scrolls — so a window tall enough for the
        // whole sheet shows the actions without a scroll, which is what this
        // test looks at. The short-window case, where the body has to be
        // scrolled, is the next test.
        //
        // The window is 360 px wide — the width the issue reports.
        runIsolatedComposeTest(width = 360, height = 2400) {
            setContent {
                SearchScreenContent(
                    state = screenState(),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            clickContentDescription("Filter")

            // The actions are the last thing in the sheet's scrollable body, so they
            // come into view by scrolling. A sheet that put them in a header at the
            // top lost them from the tree as soon as the body was scrolled; this one
            // puts them where a scroll reaches them.
            assertTagDisplayed(TestTags.SearchFilter.APPLY_BUTTON)
            assertTagDisplayed(TestTags.SearchFilter.CANCEL_BUTTON)

            clickTagFirst(TestTags.SearchFilter.CANCEL_BUTTON)
            awaitTagGone(TestTags.SearchFilter.APPLY_BUTTON)
        }

    @Test
    fun `filter sheet actions are reachable in a short window`() =
        // The window the issue names: 360 dp wide, and short enough that the
        // sheet's body cannot show every chip at once. The actions are the last
        // thing in the scrollable body, so a user who cannot scroll the body
        // cannot reach Apply or Cancel at all — that is the defect this
        // exercises.
        //
        // The sheet on the desktop is a window of its own, and it used to settle
        // half-expanded: measured in this window its body sat at y=432..1152, so
        // half of it was below the window's bottom edge and the switches in its
        // lower half were drawn off-screen. It now opens expanded and its body
        // fits inside the window (y=48..768).
        runIsolatedComposeTest(width = 360, height = 768) {
            setContent {
                SearchScreenContent(
                    state = screenState(),
                    onIntent = {},
                    navigator = PreviewSearchNavigator(),
                )
            }

            clickContentDescription("Filter")

            // The sheet's body is inside the window it is drawn in. A body that
            // hangs out of the window is a sheet whose lower half — the actions
            // among them — is drawn where no one can reach it.
            val body = scrollableBoundsOfTag(TestTags.SearchFilter.PINNED_SWITCH)
            val window = windowBoundsOfTag(TestTags.SearchFilter.PINNED_SWITCH)
            assertTrue(
                body.top.value >= window.top.value && body.bottom.value <= window.bottom.value,
                "the sheet's body $body hangs out of the window $window",
            )

            // A switch from the lower half of the body is on screen without a
            // scroll: it used to be drawn below the window's edge.
            assertTagDisplayed(TestTags.SearchFilter.PINNED_SWITCH)

            // Scrolling the body brings the actions into the window. Without a
            // scrollable ancestor this throws naming the missing ancestor, which
            // is the failure this test exists to catch.
            scrollToTag(TestTags.SearchFilter.APPLY_BUTTON)

            assertTagDisplayed(TestTags.SearchFilter.APPLY_BUTTON)
            assertTagDisplayed(TestTags.SearchFilter.CANCEL_BUTTON)

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
