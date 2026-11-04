@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.search.presentation.nav.LocalSearchNavigator
import com.singularity.todo.feature.search.presentation.nav.SearchNavigator
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.buildDesktopAppKoin
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlin.test.Test
import org.junit.jupiter.api.Tag as JunitTag
import org.koin.compose.KoinIsolatedContext

/**
 * Desktop JVM Compose smoke tests for the search screen's overlays.
 *
 * `SearchScreen` owns its overlays in a `rememberOverlayState<SearchDialog>`; the
 * Filter sheet is opened from the top bar, the Save dialog from the saved-search
 * row. Both are asserted open before they are dismissed — the earlier attempt at
 * this test asserted only that some node had gone afterwards, which a screen
 * that never opened the overlay would also satisfy.
 *
 * ## Why the production graph and not a hand-built ViewModel
 *
 * The screen's overlays hang off its own state, and the saved-search row only
 * renders once a saved search exists. Seeding one means writing through the real
 * repository, so the test resolves the screen's ViewModel from the app graph
 * rather than assembling `SearchViewModel`'s five collaborators by hand. The
 * graph is the one `runDesktopAppTest` uses, mounted around the screen alone so
 * the shell — which does not yet offer a route to search in a flow test — is not
 * in the way.
 *
 * Out of scope by the briefs that asked for this: the *rename* flow. #2134's
 * acceptance criteria name Filter and Save and put the full rename path out of
 * scope, and the rename overlay is reached through a long-press context menu
 * that the desktop test API cannot address (see `TestTags.SavedSearches`).
 *
 * Run with: ./gradlew :desktopApp:test --tests "*SearchScreenSheetSmokeTest"
 */
@JunitTag("slow")
@JunitTag("slow:compose")
class SearchScreenSheetSmokeTest {
    @Test
    fun `the filter sheet opens and its Cancel closes it`() =
        runIsolatedComposeTest {
            val app = buildDesktopAppKoin()
            val vm = app.koin.get<SearchViewModel>()
            setContent {
                KoinIsolatedContext(app) {
                    CompositionLocalProvider(LocalSearchNavigator provides SearchNavigator { }) {
                        SearchScreen(viewModel = vm)
                    }
                }
            }

            clickContentDescription("Filter")
            assertTagDisplayed(TestTags.SearchFilter.CANCEL_BUTTON)

            clickTag(TestTags.SearchFilter.CANCEL_BUTTON)

            awaitTagGone(TestTags.SearchFilter.CANCEL_BUTTON)
        }

    @Test
    fun `the save dialog opens from the saved-search row and its Cancel closes it`() =
        runIsolatedComposeTest {
            val app = buildDesktopAppKoin()
            val vm = app.koin.get<SearchViewModel>()
            setContent {
                KoinIsolatedContext(app) {
                    CompositionLocalProvider(LocalSearchNavigator provides SearchNavigator { }) {
                        SearchScreen(viewModel = vm)
                    }
                }
            }

            // The row that holds the save affordance renders only once a saved
            // search exists, so one is written through the same intent the UI uses.
            vm.onIntent(SearchIntent.OnSaveCurrentSearch("overdue tasks"))
            awaitTag(TestTags.SavedSearches.SAVE_CHIP)

            clickTag(TestTags.SavedSearches.SAVE_CHIP)
            assertTextDisplayed("Save Search")

            clickText("Cancel")

            onAllNodesWithText("Save Search").assertCountEquals(0)
        }
}
