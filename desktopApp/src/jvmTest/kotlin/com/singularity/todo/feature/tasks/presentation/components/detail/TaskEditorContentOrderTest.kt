@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The order of the task editor's rows.
 *
 * The rows are the fields a person fills in; the sections that add to the task are
 * long, and an empty one of them is long too. When the sections were drawn between
 * the description and the rows, a task opened on a narrow phone showed its due date
 * and its priority only after a scroll.
 *
 * The window here is 360 wide on purpose: that is the width at which the claim in
 * the issue was measured, and 800 tall, because that is the height of a phone at
 * that width (a 1080x2400 device is 360x800 dp). A shorter window would not be a
 * phone; a taller one would be a tablet, and the claim is about a phone.
 * The stub section is a tall block, because that is what the sections are — three
 * cards with a heading and an add-row each.
 *
 * Run with: ./gradlew :desktopApp:test --tests "*TaskEditorContentOrderTest"
 */
private const val CHILD_SECTION_TAG = "editor_child_section"

private const val PHONE_WIDTH = 360
private const val PHONE_HEIGHT = 800

@Tag("slow")
class TaskEditorContentOrderTest {
    @Test
    fun `the rows come before the sections that add to the task`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { editor() }

            val childTop = topOf(CHILD_SECTION_TAG)

            listOf(
                TestTags.TASK_EDITOR_PRIORITY_ROW,
                TestTags.TASK_EDITOR_DUE_ROW,
                TestTags.TASK_EDITOR_PROJECT_ROW,
                TestTags.TASK_EDITOR_TAGS_ROW,
            ).forEach { rowTag ->
                val rowTop = topOf(rowTag)
                assertTrue(
                    rowTop < childTop,
                    "The row '$rowTag' ($rowTop) must come above the child section ($childTop)",
                )
            }
        }

    @Test
    fun `the priority and the due date are on the screen without a scroll`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { editor() }

            assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_DUE_ROW)
        }

    private fun DesktopComposeUiTest.topOf(tag: String) = awaitTag(tag).fetchSemanticsNode().boundsInRoot.top
}

@Composable
private fun editor() {
    TaskEditorContent(
        model = TaskEditorModel(),
        titleSource = OwnedText("Buy oat milk", 0),
        descriptionSource = OwnedText("", 0),
        onTitleChange = {},
        onDescriptionChange = {},
        callbacks =
            TaskEditorCallbacks(
                onBack = {},
                onCheckToggle = null,
                startDate = null,
                project = RowCallbacks(onChange = {}),
                tags = RowCallbacks(onChange = {}),
                recurrence = null,
                pin = null,
                dependencies = null,
                checklist = null,
                attachments = null,
                estimate = null,
                onTimeEntryAdd = null,
                onTimeEntrySave = null,
                extraSections = {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(600.dp)
                                .testTag(CHILD_SECTION_TAG),
                    )
                },
            ),
        now = Instant.fromEpochMilliseconds(0),
        sheets = rememberDialogState(),
    )
}
