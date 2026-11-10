package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Where the editor's two supplied-content slots sit.
 *
 * The upper slot carries what asks the person for something — the first-run offer, the
 * pending proposals — and the lower slot carries the task's own state. The two are
 * separate slots precisely because their places differ: the offer belongs where the
 * person is already looking, the child work belongs below the fields they fill in.
 *
 * The bounds are read unclipped. A node below the fold still has a position, and what
 * is asserted here is the arrangement, not what happens to fit on the screen.
 */
@Tag("fast")
@OptIn(ExperimentalTestApi::class)
class TaskEditorSlotsOrderTest {
    @Test
    fun `the offer sits above the attribute rows and the child sections below them`() =
        runIsolatedComposeTest(width = 360, height = 1400) {
            setContent { editor(withOffer = true, withChild = true) }

            val offer = unclippedBoundsOfTag(TestTags.FIRST_RUN_SECTION)
            val priority = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            val pin = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PIN_ROW)
            val child = unclippedBoundsOfTag(CHILD_TAG)

            assertTrue(
                offer.bottom <= priority.top,
                "the offer is drawn below the attribute rows: offer $offer, priority $priority",
            )
            assertTrue(
                pin.bottom <= child.top,
                "the child sections are drawn above the attribute rows: pin $pin, child $child",
            )
        }

    @Test
    fun `the child sections stay below the rows when there is no offer`() =
        runIsolatedComposeTest(width = 360, height = 1400) {
            setContent { editor(withOffer = false, withChild = true) }

            assertTrue(
                onAllNodesWithTag(TestTags.FIRST_RUN_SECTION).fetchSemanticsNodes().isEmpty(),
                "an editor that was given no offer drew one",
            )

            val pin = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PIN_ROW)
            val child = unclippedBoundsOfTag(CHILD_TAG)
            assertTrue(
                pin.bottom <= child.top,
                "the child sections are drawn above the attribute rows: pin $pin, child $child",
            )
        }

    @Test
    fun `an editor filled in neither slot keeps the attribute rows where they were`() =
        runIsolatedComposeTest(width = 360, height = 1400) {
            setContent { editor(withOffer = false, withChild = false) }

            assertTrue(
                onAllNodesWithTag(TestTags.FIRST_RUN_SECTION).fetchSemanticsNodes().isEmpty(),
                "an editor that was given no offer drew one",
            )
            assertTrue(
                onAllNodesWithTag(CHILD_TAG).fetchSemanticsNodes().isEmpty(),
                "an editor that was given no child sections drew one",
            )

            // The rows keep their own order: the priority is still above the due date.
            val priority = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            val due = unclippedBoundsOfTag(TestTags.TASK_EDITOR_DUE_ROW)
            assertTrue(
                priority.bottom <= due.top,
                "the attribute rows changed their order: priority $priority, due $due",
            )
        }

    @Composable
    private fun editor(
        withOffer: Boolean,
        withChild: Boolean,
    ) {
        val offer: (@Composable () -> Unit)? =
            if (withOffer) {
                { FirstRunSection(onAskAi = {}) }
            } else {
                null
            }
        val child: (@Composable () -> Unit)? =
            if (withChild) {
                { Box(modifier = Modifier.size(40.dp).testTag(CHILD_TAG)) }
            } else {
                null
            }

        TaskEditorContent(
            model = TaskEditorModel(),
            titleSource = OwnedText(text = "", revision = 0),
            descriptionSource = OwnedText(text = "", revision = 0),
            onTitleChange = {},
            onDescriptionChange = {},
            callbacks =
                TaskEditorCallbacks(
                    onBack = {},
                    onCheckToggle = null,
                    startDate = null,
                    project = null,
                    tags = null,
                    recurrence = null,
                    pin = ToggleCallbacks(onToggle = {}),
                    dependencies = null,
                    checklist = null,
                    attachments = null,
                    estimate = null,
                    onTimeEntryAdd = null,
                    onTimeEntrySave = null,
                    bannerSections = offer,
                    extraSections = child,
                ),
            now = NOW,
            sheets = rememberDialogState<TaskEditorSheet>(),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-03-14T12:00:00Z")

        /** The child content's own tag — a slot's content is the caller's, so the tag is too. */
        const val CHILD_TAG = "editor_child_sections"
    }
}
