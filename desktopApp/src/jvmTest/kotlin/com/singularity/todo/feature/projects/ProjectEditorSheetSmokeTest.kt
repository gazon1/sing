@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.projects

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.singularity.todo.feature.projects.presentation.screen.ProjectEditorScreen
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test

@Tag("slow")
@Tag("slow:compose")
class ProjectEditorSheetSmokeTest {
    @AfterTest
    fun teardown() {
        stopKoin()
    }

    @Test
    fun `icon sheet opens and dismisses`() =
        runIsolatedComposeTest {
            setContent {
                ProjectEditorScreen(projectId = null)
            }

            clickText("Icon")
            assertTextDisplayed("Icon")
            clickText("None")
            onAllNodesWithText("Icon", substring = true).assertCountEquals(0)
        }

    @Test
    fun `parent sheet opens and dismisses`() =
        runIsolatedComposeTest {
            setContent {
                ProjectEditorScreen(projectId = null)
            }

            clickText("Parent project")
            assertTextDisplayed("Parent project")
            clickText("None (root project)")
            onAllNodesWithText("Parent project").assertCountEquals(0)
        }
}
