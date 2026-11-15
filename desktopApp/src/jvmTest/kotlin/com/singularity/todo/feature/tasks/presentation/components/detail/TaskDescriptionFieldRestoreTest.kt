package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.text.StaticText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The create draft is restored after the field has already composed.
 *
 * A `StaticText` source has no revision, so a restore that arrives on a later
 * frame is only visible through the text itself. The field composes empty, the
 * store hands the draft back, and the editor must take it — otherwise the person
 * opens Create, sees an empty description, and types over the text they wrote
 * before the process was killed.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class TaskDescriptionFieldRestoreTest {
    @Test
    fun `a static source restored after first composition reaches the editor`() =
        runIsolatedComposeTest {
            var source by mutableStateOf(StaticText(""))
            setContent {
                SingularityTheme {
                    TaskDescriptionField(
                        taskId = "t1",
                        source = source,
                        onDescriptionChange = {},
                        alwaysEditable = true,
                    )
                }
            }

            assertTagDisplayed(TestTags.TASK_EDITOR_DESCRIPTION_INPUT)

            // The draft store answers on a later frame.
            runOnIdle { source = StaticText("<p>Restored</p>") }

            assertTagTextEquals(TestTags.TASK_EDITOR_DESCRIPTION_INPUT, "Restored")
        }
}
