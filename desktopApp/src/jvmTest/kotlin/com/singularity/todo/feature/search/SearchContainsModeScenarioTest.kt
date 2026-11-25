@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for one search scenario — `SEARCH-10` (see
 * `scripts/src/traceability/scenarios/search/SEARCH-10.yaml`).
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
class SearchContainsModeScenarioTest {
    /**
     * The same query, asked a different question.
     *
     * This is the owner of `SEARCH-10`: the Android flow that used to carry it
     * (`Maestro/flows/search/09-contains-mode.yaml`) was retired — the rule is
     * asserted here over the search screen, and the flow's Android-only residue
     * (the quick-search entry path and the device's own Room index) is carried
     * by the other search flows in that area.
     *
     * The retired flow entered its text with Maestro's `inputText`, which
     * injects the text instead of typing it through the device's installed
     * keyboard, so it never owned the IME either and the retirement gives up
     * no IME coverage.
     *
     * The rule itself — the word mode rejecting the term and the contains mode
     * accepting it — is owned by `SearchContainsModeTest` in
     * `shared/src/commonTest`, and the word-mode half of it against a real
     * SQLite index by `SearchUseCaseFtsTest` in `shared/src/jvmTest`.
     */
    @Test
    @DisplayName("SEARCH-10 the contains mode finds the term in the middle of a word")
    fun the_contains_mode_finds_the_term_in_the_middle_of_a_word() =
        runIsolatedComposeTest(width = 420, height = 900) {
            val fakes = SearchFakes()
            try {
                fakes.seedTask("Airport run")
                showSearch(fakes.viewModel())

                // The word mode: "port" is in the middle of "airport", so the
                // task is not found. The wait is on the answer — the
                // no-results state — and not on the absence of the row, which a
                // search that has not answered yet would satisfy just as well.
                typeIntoTag(TestTags.SEARCH_INPUT, "port")
                assertTagDisplayed(TestTags.SEARCH_EMPTY_RESULTS)
                assertTagNotExists(TestTags.taskItem("Airport run"))

                // The contains mode: the same query, and the task is found. The
                // mode is part of the question, so the screen asks it again.
                clickTag(TestTags.SEARCH_MODE_CONTAINS)
                assertTagDisplayed(TestTags.taskItem("Airport run"))
            } finally {
                fakes.close()
            }
        }
}
