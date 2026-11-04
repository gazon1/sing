@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The save bar's two states, mounted on its own.
 *
 * The bar is where a save announces that it is running, and the flow suite cannot
 * hold a save in flight long enough to see it. Mounting the bar directly is the
 * only way to assert the spinner on its own node — inferring it from the label
 * disappearing would pass for a bar that drew nothing at all.
 */
@Tag("fast")
class TaskSaveBarTest {
    @Test
    fun `a save in flight shows the spinner and keeps the label`() =
        runIsolatedComposeTest {
            setContent {
                TaskSaveBar(isEnabled = false, isLoading = true, onSaveClick = {})
            }

            assertTagDisplayed(TestTags.TASK_EDITOR_SAVING)
            // The label stays: the button never loses its name, and the name is what
            // a screen reader announces while the save runs.
            assertTextDisplayed("Сохранить")
        }

    @Test
    fun `an idle save bar shows no spinner`() =
        runIsolatedComposeTest {
            setContent {
                TaskSaveBar(isEnabled = true, isLoading = false, onSaveClick = {})
            }

            // Asserted on its own node: `assertCountEquals(0)` fails when the tag is
            // absent, which is the point — a bar that drew the spinner unconditionally
            // would be just as wrong as one that never drew it.
            onAllNodesWithTag(TestTags.TASK_EDITOR_SAVING).assertCountEquals(0)
            assertTextDisplayed("Сохранить")
        }
}
