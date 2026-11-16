@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.test.helpers.assertTagFocused
import com.singularity.todo.test.helpers.assertTagNotFocused
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Who takes the focus when the title row appears.
 *
 * The rule the create screen depends on: the capture case asks for the focus,
 * and nothing else does. The detail's title is text until it is tapped, and the
 * detail must never open the keyboard by itself — a detail screen that grabs
 * the keyboard on a task the person only wanted to look at costs them the
 * screen they came for.
 *
 * The focus is read from the field's `Focused` semantics: that is the same
 * thing the IME reacts to, so a test that passed while the keyboard stayed
 * closed would be testing nothing.
 */
@Tag("slow")
@Tag("slow:compose")
class TaskTitleRowFocusTest {
    @Test
    fun `the capture case puts the focus on the title`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { titleRow(alwaysEditable = true, autoFocus = true) }

            assertTagFocused(TestTags.TASK_EDITOR_TITLE_INPUT)
        }

    @Test
    fun `an editable title that did not ask for the focus does not take it`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { titleRow(alwaysEditable = true, autoFocus = false) }

            assertTagNotFocused(TestTags.TASK_EDITOR_TITLE_INPUT)
        }

    @Test
    fun `the detail's title is not focused`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent { titleRow(alwaysEditable = false, autoFocus = false) }

            assertTagNotFocused(TestTags.TASK_EDITOR_TITLE_INPUT)
        }

    @Composable
    private fun titleRow(
        alwaysEditable: Boolean,
        autoFocus: Boolean,
    ) {
        SingularityTheme(themeMode = ThemeMode.Light) {
            TaskTitleRow(
                taskId = "t1",
                titleState = TextFieldState(""),
                isCompleted = false,
                onCheckToggle = null,
                alwaysEditable = alwaysEditable,
                autoFocus = autoFocus,
            )
        }
    }
}
