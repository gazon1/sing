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
 *
 * This is a window size, not a new harness: `runIsolatedComposeTest` has taken
 * `width` and `height` for as long as the month-grid tests have needed a narrow
 * window (`IsolatedComposeTest.kt`), and `runDesktopAppTest` takes the same two
 * since this change, because a claim about a 360 dp phone cannot be measured in a
 * 1024-wide window.
 * The stub section is a tall block, because that is what the sections are — three
 * cards with a heading and an add-row each.
 *
 * Run with: ./gradlew :desktopApp:test --tests "*TaskEditorContentOrderTest"
 */
private const val CHILD_SECTION_TAG = "editor_child_section"

/**
 * The upper slot's stub.
 *
 * An offer card is one card, not a growing list, so the stub is short — long enough to
 * have a position, small enough that the "no scroll" claim about the rows below it is
 * still a claim about a phone.
 */
private const val ASK_SECTION_TAG = "editor_ask_section"

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

    /**
     * The two supplied-content slots, in opposite places.
     *
     * They carry opposite kinds of content — an ask and the task's own state — and
     * they shared one slot until the child sections moved below the rows, which moved
     * the offers and the pending proposals down with them. This is the check that
     * fails if the two are ever merged back, or if either is drawn on the wrong side.
     */
    @Test
    fun `the ask goes above the rows and the child sections below them`() =
        runIsolatedComposeTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { editor() }

            val askTop = topOf(ASK_SECTION_TAG)
            val childTop = topOf(CHILD_SECTION_TAG)
            assertTrue(
                askTop < childTop,
                "The ask slot ($askTop) must be drawn above the child sections ($childTop)",
            )

            listOf(
                TestTags.TASK_EDITOR_PRIORITY_ROW,
                TestTags.TASK_EDITOR_DUE_ROW,
                TestTags.TASK_EDITOR_PROJECT_ROW,
            ).forEach { rowTag ->
                val rowTop = topOf(rowTag)
                assertTrue(
                    askTop < rowTop,
                    "The ask slot ($askTop) must come above the row '$rowTag' ($rowTop) — " +
                        "content that asks for a decision belongs under the description, " +
                        "not under the fields.",
                )
                assertTrue(
                    rowTop < childTop,
                    "The row '$rowTag' ($rowTop) must come above the child section ($childTop)",
                )
            }
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
                askSections = {
                    // An offer card's height, not a growing list's: the upper slot holds
                    // one card, and the claim about the rows below it is a claim about a
                    // phone.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .testTag(ASK_SECTION_TAG),
                    )
                },
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
