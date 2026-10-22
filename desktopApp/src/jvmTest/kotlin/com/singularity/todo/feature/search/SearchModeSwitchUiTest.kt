@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.search.presentation.SearchModeSwitch
import com.singularity.todo.feature.search.query.SearchMode
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The switch that states which question the search is asking.
 *
 * The mode is part of the question, so it has to be visible where the query is
 * written — and the contains mode has to say what it costs, because it is the
 * mode that reads the rows instead of asking the index.
 *
 * Run with: ./gradlew :desktopApp:test --tests "*SearchModeSwitchUiTest"
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class SearchModeSwitchUiTest {

    @Test
    fun `the switch shows both modes`() = runIsolatedComposeTest {
        setContent {
            SearchModeSwitch(mode = SearchMode.Word, onModeChange = {})
        }

        assertTagDisplayed(TestTags.SEARCH_MODE_WORD)
        assertTagDisplayed(TestTags.SEARCH_MODE_CONTAINS)
    }

    @Test
    fun `the word mode says nothing about the cost`() = runIsolatedComposeTest {
        setContent {
            SearchModeSwitch(mode = SearchMode.Word, onModeChange = {})
        }

        assertTagNotExists(TestTags.SEARCH_MODE_CONTAINS_NOTE)
    }

    @Test
    fun `the contains mode says what it costs`() = runIsolatedComposeTest {
        setContent {
            SearchModeSwitch(mode = SearchMode.Contains, onModeChange = {})
        }

        assertTagDisplayed(TestTags.SEARCH_MODE_CONTAINS_NOTE)
    }

    @Test
    fun `choosing the contains mode says so`() = runIsolatedComposeTest {
        var chosen: SearchMode? = null
        setContent {
            SearchModeSwitch(mode = SearchMode.Word, onModeChange = { chosen = it })
        }

        clickText("Contains")

        assertEquals(SearchMode.Contains, chosen)
    }
}
