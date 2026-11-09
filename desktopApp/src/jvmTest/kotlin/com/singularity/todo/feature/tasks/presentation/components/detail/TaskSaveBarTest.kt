package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagEnabled
import com.singularity.todo.test.helpers.assertTagNotEnabled
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskSaveBarTest {
    @Test
    fun a_save_in_flight_shows_the_spinner_not_the_label() =
        runDesktopAppTest {
            setContent {
                TaskSaveBar(isEnabled = false, isLoading = true, onSaveClick = {})
            }

            assertTagNotEnabled(TestTags.TASK_EDITOR_SAVE)
            assertTagDisplayed(TestTags.TASK_EDITOR_SAVING)
            assertTextNotExists("Сохранить")
        }

    @Test
    fun an_idle_bar_shows_the_label_not_the_spinner() =
        runDesktopAppTest {
            setContent {
                TaskSaveBar(isEnabled = true, isLoading = false, onSaveClick = {})
            }

            assertTagEnabled(TestTags.TASK_EDITOR_SAVE)
            assertTagNotExists(TestTags.TASK_EDITOR_SAVING)
        }

    @Test
    fun loading_state_depends_on_isLoading_when_enabled() =
        runDesktopAppTest {
            setContent {
                TaskSaveBar(isEnabled = true, isLoading = true, onSaveClick = {})
            }

            assertTagDisplayed(TestTags.TASK_EDITOR_SAVING)
            assertTextNotExists("Сохранить")
        }

    @Test
    fun idle_state_depends_on_isLoading_when_disabled() =
        runDesktopAppTest {
            setContent {
                TaskSaveBar(isEnabled = false, isLoading = false, onSaveClick = {})
            }

            assertTagNotExists(TestTags.TASK_EDITOR_SAVING)
        }
}
