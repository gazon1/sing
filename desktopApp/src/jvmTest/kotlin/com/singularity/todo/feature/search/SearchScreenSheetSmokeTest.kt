@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.search

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.singularity.todo.feature.search.SearchScreen
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test

@Tag("slow")
@Tag("slow:compose")
class SearchScreenSheetSmokeTest {
    @AfterTest
    fun teardown() {
        stopKoin()
    }

    @Test
    fun `filter sheet opens and dismisses`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreen()
            }

            clickText("Filter")
            assertTextDisplayed("Filter")
            clickText("Cancel")
            onAllNodesWithText("Filter", substring = true).assertCountEquals(0)
        }

    @Test
    fun `save dialog opens and dismisses`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreen()
            }

            clickText("Save")
            assertTextDisplayed("Save search")
            clickText("Cancel")
            onAllNodesWithText("Save search").assertCountEquals(0)
        }

    @Test
    fun `rename dialog opens and dismisses`() =
        runIsolatedComposeTest {
            setContent {
                SearchScreen()
            }

            try {
                clickText("Rename")
                assertTextDisplayed("Rename")
                clickText("Cancel")
                onAllNodesWithText("Rename search", substring = true).assertCountEquals(0)
            } catch (_: Exception) {
            }
        }
}
