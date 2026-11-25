@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.search.presentation.nav.LocalSearchNavigator
import com.singularity.todo.feature.search.presentation.nav.PreviewSearchNavigator
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagFocused
import com.singularity.todo.test.helpers.assertTagNotFocused
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.awaitTagTextEquals
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for one search scenario — `SEARCH-11` (see
 * `scripts/src/traceability/scenarios/search/SEARCH-11.yaml`) — and for the
 * affordances the search field carries beside it.
 *
 * The `@DisplayName` below is the linkage. The id is the **first token** of the
 * string, and that strictness is the contract: the traceability scanner rejects
 * an id anywhere else rather than guessing. Only the methods that carry an id
 * claim a scenario; the rest of the class asserts affordances no scenario id
 * owns.
 *
 * ## What these tests mount, and why not the whole app
 *
 * They mount the search screen with a real `SearchViewModel` over the test
 * fakes, not the `App()` the desktop flow tests mount. The pending state is the
 * reason: the search is debounced and then run, and a flow test's clock advances
 * past both before an assertion can look — so "the answer has not come yet" is a
 * state a flow test cannot hold still. A resolver that waits on a gate holds it
 * still, and lets the same test then release the gate and assert the answer
 * arriving.
 *
 * The screen is otherwise the screen the app draws: the field, its clear button,
 * the mode switch and the outcome all come from `SearchScreen`.
 *
 * ## Why this file carries one claim
 *
 * The traceability scanner allows one scenario claim per carrier file, and the
 * result matrix rejects a file two scenarios claim as ambiguous. The other
 * search scenarios therefore live in their own files — `SearchClearScenarioTest`
 * and `SearchWordPrefixScenarioTest` — and the machinery they share lives in
 * `SearchScreenHarness`.
 */
@Tag("slow")
@Tag("slow:compose")
class SearchPendingStateScenarioTest {
    /**
     * The query is stated and the answer has not come: the screen says so, and
     * does not claim the search found nothing.
     *
     * This is the defect the scenario exists for. Before it, a non-blank query
     * with no results was reported as "No results found" — confident, wrong, and
     * replaced by the real results a moment later.
     */
    @Test
    @DisplayName("SEARCH-11 the screen says the search is running before it answers")
    fun the_screen_says_the_search_is_running_before_it_answers() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                fakes.seedTask("Buy milk")
                val gate = CompletableDeferred<Unit>()
                val viewModel = fakes.viewModel(GatedResolver(gate, fakes.resolver()))

                showSearch(viewModel)

                assertTagDisplayed(TestTags.SEARCH_EMPTY_PLACEHOLDER)

                typeIntoTag(TestTags.SEARCH_INPUT, "milk")

                // The question is being asked: the indicator is on screen and the
                // empty answer is not stated.
                assertTagDisplayed(TestTags.SEARCH_LOADING)
                awaitTagGone(TestTags.SEARCH_EMPTY_RESULTS)

                // The answer arrives. The loading tag leaving is the wait; the
                // result is asserted after it, because an immediate assertion
                // would be racing the search rather than observing it.
                gate.complete(Unit)

                awaitTagGone(TestTags.SEARCH_LOADING)
                assertTagDisplayed(TestTags.taskItem("Buy milk"))
            } finally {
                fakes.close()
            }
        }

    /**
     * The field asks for the focus when the screen appears.
     *
     * A screen opened in order to be typed into should put the caret in its
     * field; without the request the user has to find the field and tap it.
     */
    @Test
    fun the_query_field_holds_the_focus_when_the_screen_appears() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                showSearch(fakes.viewModel())

                assertTagFocused(TestTags.SEARCH_INPUT)
            } finally {
                fakes.close()
            }
        }

    /** Losing the focus is not a write: the query survives the caret leaving the field. */
    @Test
    fun the_field_gives_up_the_focus_without_losing_the_query() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                val viewModel = fakes.viewModel()
                var focusManager: FocusManager? = null
                setContent {
                    SingularityTheme(themeMode = ThemeMode.Light) {
                        CompositionLocalProvider(LocalSearchNavigator provides PreviewSearchNavigator()) {
                            focusManager = LocalFocusManager.current
                            SearchScreen(viewModel = viewModel)
                        }
                    }
                }

                assertTagFocused(TestTags.SEARCH_INPUT)
                typeIntoTag(TestTags.SEARCH_INPUT, "milk")

                runOnIdle { focusManager?.clearFocus() }

                assertTagNotFocused(TestTags.SEARCH_INPUT)
                assertTagTextEquals(TestTags.SEARCH_INPUT, "milk")
            } finally {
                fakes.close()
            }
        }

    /** A saved search seeds the field with the exact query it stores. */
    @Test
    fun a_saved_search_seeds_the_field_with_its_stored_query() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                fakes.seedSavedSearch(id = "ss-high", name = "High priority", query = "priority:high")
                showSearch(fakes.viewModel())

                clickText("High priority")

                awaitTagTextEquals(TestTags.SEARCH_INPUT, "priority:high")
                // The selection is a dismissal, and the row it was selected from is
                // the row that goes.
                awaitTagGone(TestTags.SavedSearches.chip("High priority"))
            } finally {
                fakes.close()
            }
        }

    /**
     * A saved search may carry an empty query, and loading it must not read as a
     * clear.
     *
     * This is the ordering the screen's rule is about, and it is asked of the real
     * ViewModel, the real bridge and the real field: the ViewModel writes the
     * active id and the query, the field is pulled to the query a frame later, and
     * the screen must still tell "the field is empty because a search was loaded"
     * from "the field is empty because the person cleared it". The first keeps the
     * row down; the second brings it back.
     */
    @Test
    fun a_saved_search_with_an_empty_query_keeps_the_row_down() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                // The name is what makes it a suggestion for "milk"; the stored
                // query is empty, which is what the screen must not read as a clear.
                fakes.seedSavedSearch(id = "ss-empty", name = "Milk everything", query = "")
                showSearch(fakes.viewModel())

                typeIntoTag(TestTags.SEARCH_INPUT, "milk")
                awaitTagGone(TestTags.SEARCH_LOADING)
                assertTagDisplayed(TestTags.SavedSearches.chip("Milk everything"))

                clickTag(TestTags.SavedSearches.chip("Milk everything"))

                // The load empties the field. The field being empty is not a clear.
                awaitTagTextEquals(TestTags.SEARCH_INPUT, "")
                awaitTagGone(TestTags.SavedSearches.chip("Milk everything"))
                assertTagDisplayed(TestTags.SavedSearches.SAVE_CHIP)

                // And the other reading of the same empty field does bring it back:
                // this is what makes the pair of tests a contract rather than a
                // snapshot of one state.
                clickTag(TestTags.SEARCH_INPUT)
                assertTagDisplayed(TestTags.SavedSearches.chip("Milk everything"))
            } finally {
                fakes.close()
            }
        }
}
