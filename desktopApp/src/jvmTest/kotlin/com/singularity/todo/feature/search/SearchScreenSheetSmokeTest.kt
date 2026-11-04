@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
import com.singularity.todo.feature.search.domain.SearchFilter
import com.singularity.todo.feature.search.presentation.RenameSearchDialog
import com.singularity.todo.feature.search.presentation.SaveSearchDialog
import com.singularity.todo.feature.search.presentation.SavedSearchesRow
import com.singularity.todo.feature.search.presentation.SimpleFilterSheet
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTextGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.longClickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Open+dismiss smoke tests for the search screen's overlays (#2134, #1469).
 *
 * ## Every test opens its overlay, then dismisses it
 *
 * Each test starts with the overlay *closed* and asserts so, then presses
 * something that opens it, asserts it is on screen, and only then dismisses it.
 * The previous version of this file started the filter sheet and the save dialog
 * already open, so a regression in the code that opens them passed both tests
 * green — only the dismiss was proven.
 *
 * Where the screen offers an affordance the isolated harness can reach, the test
 * uses it: the save dialog opens from the saved-search row's Save chip and the
 * rename dialog from a chip's long-press menu, exactly as a person opens them.
 * The filter sheet's only entry point is the search bar's filter action inside
 * `SearchScreen()`, which resolves its ViewModel through `koinViewModel()` — the
 * isolated harness starts no Koin graph — so that button is replaced by a local
 * one whose click does what the search bar's does. The substitution is stated
 * here rather than hidden.
 *
 * ## Why the sentinels are counts, not sentinel strings
 *
 * "Cancel must not apply anything" cannot be shown by a variable that starts at
 * a marker value: a callback whose parameter type changes keeps compiling and
 * keeps passing. Each assertion below is a call count (zero for the action a
 * cancel must not perform, one for the dismiss it must) plus a typed `null`.
 *
 * ## Why the window is 420×1600
 *
 * `SimpleFilterSheet` is a `ModalBottomSheet`, which draws in its own window of
 * a fixed height (~1080) that does not follow the test window; at the default
 * 768 px its content ends up below the window, composed but untappable. The two
 * dialogs get the same window so all three tests read alike.
 */
@Tag("slow")
@Tag("slow:compose")
class SearchScreenSheetSmokeTest {
    private val epoch = Instant.fromEpochMilliseconds(0L)

    private fun makeSavedSearch(
        id: String,
        name: String,
        query: String = "query:$id",
    ): SavedSearch =
        SavedSearch(
            id = SavedSearchId(id),
            userId = UserId("test-user"),
            name = name,
            queryString = query,
            createdAt = epoch,
            updatedAt = epoch,
        )

    @Test
    fun `filter sheet opens and dismisses`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            var open by mutableStateOf(false)
            var dismissCalls = 0
            var applyCalls = 0
            var applied: SearchFilter? = null

            setContent {
                // Stands in for the search bar's filter action: that button lives in
                // SearchScreen, which needs Koin to resolve its ViewModel.
                TextButton(onClick = { open = true }) { Text("Open filter") }

                if (open) {
                    SimpleFilterSheet(
                        initialFilter = null,
                        onApply = {
                            applyCalls++
                            applied = it
                        },
                        onDismiss = {
                            dismissCalls++
                            open = false
                        },
                    )
                }
            }

            // Closed: the sheet's own Apply action is not on screen yet.
            onAllNodesWithText("Apply").assertCountEquals(0)

            clickText("Open filter")

            // Open: the sheet's own Apply action is on screen.
            assertTextDisplayed("Apply")

            clickText("Cancel")

            // Dismiss: the callback fired, the sheet left the scene, and a cancel
            // applied no filter.
            assertEquals(1, dismissCalls, "the filter sheet's dismiss callback must fire")
            assertEquals(0, applyCalls, "cancel must not apply a filter")
            assertNull(applied, "cancel must not apply a filter")
            awaitTextGone("Apply")
        }

    @Test
    fun `save dialog opens from the saved-search row and dismisses`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            var open by mutableStateOf(false)
            var dismissCalls = 0
            var saveCalls = 0
            var savedName: String? = null

            setContent {
                SavedSearchesRow(
                    savedSearches = persistentListOf(),
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    // The row's real Save chip — the entry point a person uses.
                    onSaveClick = { open = true },
                    onRename = { _, _ -> },
                    onDelete = {},
                )

                if (open) {
                    SaveSearchDialog(
                        initialName = "work tasks",
                        onDismiss = {
                            dismissCalls++
                            open = false
                        },
                        onSave = {
                            saveCalls++
                            savedName = it
                        },
                    )
                }
            }

            // Closed: the dialog's title is absent. The row's chip reads "Save";
            // the dialog reads "Save Search".
            onAllNodesWithText("Save Search").assertCountEquals(0)

            clickText("Save")

            // Open: the dialog's own title is on screen.
            assertTextDisplayed("Save Search")

            clickText("Cancel")

            // Dismiss: the callback fired, the dialog left the scene, and a cancel
            // saved nothing.
            assertEquals(1, dismissCalls, "the save dialog's dismiss callback must fire")
            assertEquals(0, saveCalls, "cancel must not save the search")
            assertNull(savedName, "cancel must not save the search")
            awaitTextGone("Save Search")
        }

    @Test
    fun `rename dialog opens from the saved-search row and dismisses`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            val savedSearch = makeSavedSearch("ss1", "High Priority", "priority:high")
            var renaming by mutableStateOf<Pair<SavedSearchId, String>?>(null)
            var dismissCalls = 0
            var renameCalls = 0
            var renamedTo: String? = null

            setContent {
                SavedSearchesRow(
                    savedSearches = listOf(savedSearch).toImmutableList(),
                    activeSavedSearchId = null,
                    onLoadSearch = {},
                    onSaveClick = {},
                    onRename = { id, name -> renaming = id to name },
                    onDelete = {},
                )

                val target = renaming
                if (target != null) {
                    RenameSearchDialog(
                        currentName = target.second,
                        onDismiss = {
                            dismissCalls++
                            renaming = null
                        },
                        onRename = {
                            renameCalls++
                            renamedTo = it
                        },
                    )
                }
            }

            // Closed: the dialog's title is not on screen behind the row.
            onAllNodesWithText("Rename Search").assertCountEquals(0)

            // The row is on screen and carries the chip the menu hangs off,
            // addressed by the saved search's id — its name is user-controlled and
            // not unique, so it cannot be the selector.
            assertTextDisplayed("High Priority")

            // Open: long-press the chip, then submit the menu's rename action. The
            // menu renders in its own window, which the scene's cross-root search
            // reaches; either step fails loudly if it does not happen.
            longClickTag(TestTags.SavedSearches.savedSearchChip(savedSearch.id.raw))
            clickTag(TestTags.SavedSearches.SEARCH_MENU_RENAME_CONFIRM)

            // Open, asserted before the dismiss: the dialog's own title is on
            // screen — the row behind it carries no such text.
            assertTextDisplayed("Rename Search")

            clickText("Cancel")

            // Dismiss: the callback fired, the dialog left the scene, and a cancel
            // renamed nothing.
            assertEquals(1, dismissCalls, "the rename dialog's dismiss callback must fire")
            assertEquals(0, renameCalls, "cancel must not rename the saved search")
            assertNull(renamedTo, "cancel must not rename the saved search")
            awaitTextGone("Rename Search")
        }
}
