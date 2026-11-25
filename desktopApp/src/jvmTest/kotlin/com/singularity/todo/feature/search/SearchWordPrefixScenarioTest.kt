@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for one search scenario — `SEARCH-08` (see
 * `scripts/src/traceability/scenarios/search/SEARCH-08.yaml`).
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
class SearchWordPrefixScenarioTest {
    /**
     * The word mode finds a word by its beginning and not by its middle.
     *
     * This is the owner of `SEARCH-08`: the Android flow that used to carry it
     * (`Maestro/flows/search/08-word-prefix.yaml`) was retired — the rule is
     * asserted here over the search screen, and the flow's Android-only residue
     * (the quick-search entry path and the device's own Room index) is carried
     * by the other search flows in that area.
     *
     * The retired flow entered its text with Maestro's `inputText`, which
     * injects the text instead of typing it through the device's installed
     * keyboard, so it never owned the IME either and the retirement gives up
     * no IME coverage.
     *
     * The index half of the same rule — the FTS expression behind these two
     * answers — is owned by `SearchUseCaseFtsTest` in `shared/src/jvmTest`,
     * against a real SQLite.
     */
    @Test
    @DisplayName("SEARCH-08 the word mode finds the beginning of a word and not its middle")
    fun the_word_mode_finds_the_beginning_of_a_word_and_not_its_middle() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                fakes.seedTask("Architecture of the house")
                fakes.seedTask("Airport run")
                showSearch(fakes.viewModel())

                // "arch" is the beginning of a word: the task is found. The row
                // being there is the proof that the search has answered.
                typeIntoTag(TestTags.SEARCH_INPUT, "arch")
                assertTagDisplayed(TestTags.taskItem("Architecture of the house"))
                assertTagNotExists(TestTags.taskItem("Airport run"))

                // "port" is the middle of a word: the airport task is not found.
                // The wait is on the answer — the no-results state — and not on
                // the absence of the row, which a search that has not answered
                // yet would satisfy just as well.
                typeIntoTag(TestTags.SEARCH_INPUT, "port")
                assertTagDisplayed(TestTags.SEARCH_EMPTY_RESULTS)
                assertTagNotExists(TestTags.taskItem("Airport run"))
            } finally {
                fakes.close()
            }
        }
}
