@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.awaitTagTextEquals
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for one search scenario — `SEARCH-03` (see
 * `scripts/src/traceability/scenarios/search/SEARCH-03.yaml`).
 *
 * The `@DisplayName` below is the linkage: the id is the **first token** of the
 * string, and the traceability scanner rejects an id anywhere else rather than
 * guessing.
 *
 * ## Why this file carries one claim
 *
 * The traceability scanner allows one scenario claim per carrier file, and the
 * result matrix rejects a file two scenarios claim as ambiguous. The other
 * search scenarios therefore live in their own files, and the machinery they
 * share lives in `SearchScreenHarness`.
 */
@Tag("slow")
@Tag("slow:compose")
class SearchClearScenarioTest {
    /**
     * The clear button empties the query, and the screen returns to the state it
     * was in before anything was typed.
     *
     * The button is the field's own: clearing a query used to mean long-press,
     * "Select all", delete.
     *
     * This is the owner of `SEARCH-03`: the Android flow that used to carry it
     * (`Maestro/flows/search/03-clear.yaml`) was retired — the shared behaviour
     * is asserted here, and the flow's Android-only residue (the quick-search
     * entry path) is carried by the other search flows.
     *
     * The retired flow entered its text with Maestro's `inputText`, which
     * injects the text instead of typing it through the device's installed
     * keyboard, so it never owned the IME either and the retirement gives up
     * no IME coverage.
     */
    @Test
    @DisplayName("SEARCH-03 the clear button empties the query and the idle prompt returns")
    fun the_clear_button_empties_the_query_and_the_idle_prompt_returns() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                fakes.seedTask("Buy milk")
                showSearch(fakes.viewModel())

                typeIntoTag(TestTags.SEARCH_INPUT, "milk")
                // The answer is asynchronous: wait for it before asserting it.
                awaitTagGone(TestTags.SEARCH_LOADING)
                assertTagDisplayed(TestTags.taskItem("Buy milk"))

                clickTag(TestTags.SEARCH_CLEAR_BUTTON)

                // The query itself is empty — the field, not only the results.
                awaitTagTextEquals(TestTags.SEARCH_INPUT, "")
                assertTagDisplayed(TestTags.SEARCH_EMPTY_PLACEHOLDER)
                awaitTagGone(TestTags.taskItem("Buy milk"))
                // There is nothing left to clear, so the field no longer offers the
                // control: a button that clears nothing is a button that does nothing.
                awaitTagGone(TestTags.SEARCH_CLEAR_BUTTON)
            } finally {
                fakes.close()
            }
        }
}
