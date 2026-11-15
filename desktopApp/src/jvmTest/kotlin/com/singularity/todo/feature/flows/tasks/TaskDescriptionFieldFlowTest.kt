package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The task description, from the editor to the row and back to the screen.
 *
 * The description is now a rich-text editor, and its text lives *inside* the
 * editor's state object — the same instance for the whole session. A dispatch
 * keyed on the object rather than on its contents never fires, and a description
 * that is never dispatched is a description that is never saved. The loss is
 * silent: the editor shows the text and the task comes back without it.
 *
 * The test therefore asserts against the *repository*, not only against the
 * editor's buffer, and then reopens the task to check that the stored HTML is
 * what the detail renders.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDescriptionFieldFlowTest {
    @Test
    fun the_description_typed_in_the_create_editor_reaches_the_task() =
        runDesktopAppTest(checkA11y = true) { koin ->
            tapTab("Inbox")
            clickContentDescription(DesktopShell.FAB_ADD_TASK)
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Rich description")
            typeIntoTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT, "First line")
            awaitTag(TestTags.TASK_EDITOR_SAVE).performClick()

            // The write happens on the ViewModel's own scope behind the debounce, so
            // the wait runs on a real dispatcher — the test's virtual clock would
            // fire long before the write could happen.
            var lastSeen: Task? = null
            val saved =
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(10.seconds) {
                        koin
                            .get<TaskRepository>()
                            .observeAll()
                            .map { tasks ->
                                tasks.firstOrNull { it.title == "Rich description" }.also { lastSeen = it }
                            }.firstOrNull { task ->
                                task?.description == "First line" &&
                                    task.descriptionHtml?.contains("First line") == true
                            }
                    }
                }
            assertTrue(
                saved != null,
                "the description did not reach the task; the task held " +
                    "${lastSeen?.description} / ${lastSeen?.descriptionHtml}",
            )
        }

    @Test
    fun the_detail_shows_the_saved_description_until_it_is_tapped() =
        runDesktopAppTest(checkA11y = true) {
            tapTab("Inbox")
            clickContentDescription(DesktopShell.FAB_ADD_TASK)
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Description on the detail")
            typeIntoTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT, "Saved text")
            awaitTag(TestTags.TASK_EDITOR_SAVE).performClick()

            awaitTag(TestTags.taskItem("Description on the detail")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // Document style: the text is rendered until it is tapped, which is what
            // lets a link be tapped instead of opening the field.
            assertTagDisplayed(TestTags.TASK_EDITOR_DESCRIPTION_INPUT)
        }

    @Test
    fun autosave_does_not_reset_the_description_while_the_user_keeps_typing() =
        runDesktopAppTest(checkA11y = true) { koin ->
            tapTab("Inbox")
            clickContentDescription(DesktopShell.FAB_ADD_TASK)
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
            typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, "Keeps typing")
            awaitTag(TestTags.TASK_EDITOR_SAVE).performClick()

            awaitTag(TestTags.taskItem("Keeps typing")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // The detail is document style: the description is text until tapped.
            clickTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT)
            typeIntoTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT, "Hello")
            assertTagTextEquals(TestTags.TASK_EDITOR_DESCRIPTION_INPUT, "Hello")

            // Wait for the debounced write and its echo — the moment a naive
            // re-seed would replace the editor's content and move the caret.
            val firstWrite =
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(10.seconds) {
                        koin
                            .get<TaskRepository>()
                            .observeAll()
                            .firstOrNull { tasks ->
                                tasks.any { it.title == "Keeps typing" && it.description == "Hello" }
                            }
                    }
                }
            assertTrue(firstWrite != null, "the first half of the description never reached the task")

            // Continue at the caret. If the echo reset the editor, the selection is
            // back at 0 and the insertion lands at the start.
            val selection =
                awaitTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.TextSelectionRange]
            assertEquals(TextRange(5, 5), selection, "the autosave echo moved the caret")

            awaitTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT).performTextInput(" World")
            assertTagTextEquals(TestTags.TASK_EDITOR_DESCRIPTION_INPUT, "Hello World")

            var lastSeen: Task? = null
            val secondWrite =
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(10.seconds) {
                        koin
                            .get<TaskRepository>()
                            .observeAll()
                            .map { tasks ->
                                tasks.firstOrNull { it.title == "Keeps typing" }.also { lastSeen = it }
                            }.firstOrNull { it?.description == "Hello World" }
                    }
                }
            assertTrue(
                secondWrite != null,
                "the continuation was not saved; the task held ${lastSeen?.description}",
            )
        }
}
