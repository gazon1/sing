@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.search.domain.SavedSearch
import com.singularity.todo.feature.search.domain.SavedSearchId
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
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Open+dismiss smoke tests for the search screen's overlays.
 *
 * ## Why these mount the surfaces rather than `SearchScreen()`
 *
 * `SearchScreen()` resolves its ViewModel through `koinViewModel()`, and the
 * isolated harness starts no Koin graph — mounting the screen throws before an
 * overlay is reached. The screen is a thin host: it owns one overlay state and
 * hands each overlay a dismiss callback and the action it performs. These tests
 * mount the same surfaces with the same wiring, so an overlay that stops
 * opening, or a dismiss that stops closing it, fails here.
 *
 * The rename overlay is the awkward one: its only entry point is the long-press
 * menu on a saved-search chip. The test mounts [SavedSearchesRow] with one saved
 * search and lets the row's `onRename` open [RenameSearchDialog] — exactly what
 * the screen does. An earlier version of this test wrapped the whole sequence in
 * `try/catch` and passed even when the chip was never there; there is no
 * `try/catch` now, and the open is asserted before the dismiss.
 *
 * ## Why the window is 420×1600
 *
 * `SimpleFilterSheet` is a `ModalBottomSheet`, which draws in its own window of
 * a fixed height (~1080) that does not follow the test window; at the default
 * 768 px its content ends up below the window, composed but untappable.
 */
@Tag("slow")
@Tag("slow:compose")
class SearchScreenSheetSmokeTest {
    @Test
    fun `filter sheet opens and dismisses`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            var open by mutableStateOf(true)
            var dismissed = false
            var applied: Any? = "sentinel"

            setContent {
                if (open) {
                    SimpleFilterSheet(
                        initialFilter = null,
                        onApply = { applied = it },
                        onDismiss = {
                            dismissed = true
                            open = false
                        },
                    )
                }
            }

            // Open: the sheet's own Apply action is on screen.
            assertTextDisplayed("Apply")

            clickText("Cancel")

            // Dismiss: the callback fired, the sheet left the scene, and a
            // cancel applied no filter.
            assertTrue(dismissed, "the filter sheet's dismiss callback must fire")
            assertEquals("sentinel", applied, "cancel must not apply a filter")
            awaitTextGone("Apply")
        }

    @Test
    fun `save dialog opens and dismisses`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            var open by mutableStateOf(true)
            var dismissed = false
            var saved: String? = "sentinel"

            setContent {
                if (open) {
                    SaveSearchDialog(
                        initialName = "work tasks",
                        onDismiss = {
                            dismissed = true
                            open = false
                        },
                        onSave = { saved = it },
                    )
                }
            }

            // Open: the dialog's own title is on screen.
            assertTextDisplayed("Save Search")

            clickText("Cancel")

            // Dismiss: the callback fired, the dialog left the scene, and a
            // cancel saved nothing.
            assertTrue(dismissed, "the save dialog's dismiss callback must fire")
            assertEquals("sentinel", saved, "cancel must not save the search")
            awaitTextGone("Save Search")
        }

    @Test
    fun `rename dialog opens from the saved-search row and dismisses`() =
        runIsolatedComposeTest(width = 420, height = 1600) {
            val savedSearch =
                SavedSearch(
                    id = SavedSearchId("ss1"),
                    userId = UserId("test-user"),
                    name = "High Priority",
                    queryString = "priority:high",
                    createdAt = Instant.fromEpochMilliseconds(0L),
                    updatedAt = Instant.fromEpochMilliseconds(0L),
                )
            var renaming by mutableStateOf<Pair<SavedSearchId, String>?>(null)
            var dismissed = false
            var renamedTo: String? = "sentinel"

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
                            dismissed = true
                            renaming = null
                        },
                        onRename = { renamedTo = it },
                    )
                }
            }

            // The row is on screen and carries the chip the menu hangs off.
            assertTextDisplayed("High Priority")

            // Open: long-press the chip, then submit the menu's rename action.
            // The menu renders in its own window, which the scene's cross-root
            // search reaches; either step fails loudly if it does not happen.
            longClickTag(TestTags.SavedSearches.savedSearchChip(savedSearch.name))
            clickTag(TestTags.SavedSearches.SEARCH_MENU_RENAME_CONFIRM)

            // Open, asserted before the dismiss: the dialog's own title is on
            // screen — the row behind it carries no such text.
            assertTextDisplayed("Rename Search")

            clickText("Cancel")

            // Dismiss: the callback fired, the dialog left the scene, and a
            // cancel renamed nothing.
            assertTrue(dismissed, "the rename dialog's dismiss callback must fire")
            assertEquals("sentinel", renamedTo, "cancel must not rename the saved search")
            awaitTextGone("Rename Search")
        }
}
