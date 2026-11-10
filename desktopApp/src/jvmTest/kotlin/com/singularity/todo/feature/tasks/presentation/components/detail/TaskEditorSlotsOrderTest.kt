package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
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

            assertTagNotExists(TestTags.FIRST_RUN_SECTION)

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

            assertTagNotExists(TestTags.FIRST_RUN_SECTION)
            assertTagNotExists(CHILD_TAG)

            // The rows keep their own order: the priority is still above the due date.
            val priority = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            val due = unclippedBoundsOfTag(TestTags.TASK_EDITOR_DUE_ROW)
            assertTrue(
                priority.bottom <= due.top,
                "the attribute rows changed their order: priority $priority, due $due",
            )
        }

    /**
     * #2010: the dependencies card joins the attribute group after the due date instead
     * of leading it. The rows a person fills in must not move when a relation exists.
     */
    @Test
    fun `the dependencies card does not push the priority and the due date down`() =
        runIsolatedComposeTest(width = 360, height = 1400) {
            val withDependencies = mutableStateOf(false)
            setContent {
                editor(
                    withOffer = false,
                    withChild = false,
                    availableTasks =
                        if (withDependencies.value) {
                            persistentListOf(testTask(id = TaskId("t2"), title = "Other task"))
                        } else {
                            persistentListOf()
                        },
                )
            }

            val priorityWithout = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            val dueWithout = unclippedBoundsOfTag(TestTags.TASK_EDITOR_DUE_ROW)

            runOnIdle { withDependencies.value = true }
            awaitTag(TestTags.TASK_EDITOR_DEPENDENCIES_ROW)

            val priorityWith = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            val dueWith = unclippedBoundsOfTag(TestTags.TASK_EDITOR_DUE_ROW)
            val dependencies = unclippedBoundsOfTag(TestTags.TASK_EDITOR_DEPENDENCIES_ROW)

            assertEquals(
                priorityWithout.top,
                priorityWith.top,
                "the dependencies card moved the priority row from " +
                    "${priorityWithout.top} to ${priorityWith.top}",
            )
            assertEquals(
                dueWithout.top,
                dueWith.top,
                "the dependencies card moved the due date from " +
                    "${dueWithout.top} to ${dueWith.top}",
            )
            assertTrue(
                priorityWith.bottom <= dueWith.top,
                "the priority row ($priorityWith) must come above the due date ($dueWith)",
            )
            assertTrue(
                dependencies.top >= dueWith.bottom,
                "the dependencies card (${dependencies.top}) must come after the due date " +
                    "(${dueWith.top}..${dueWith.bottom}), not lead the attribute group",
            )
        }

    @Test
    fun `the dependencies card opens the picker`() =
        runIsolatedComposeTest(width = 360, height = 1400) {
            setContent {
                editor(
                    withOffer = false,
                    withChild = false,
                    availableTasks = persistentListOf(testTask(id = TaskId("t2"), title = "Other task")),
                )
            }

            awaitTag(TestTags.TASK_EDITOR_DEPENDENCIES_ROW).performClick()
            awaitTag(TestTags.dependencyItem("t2"))
        }

    @Composable
    private fun editor(
        withOffer: Boolean,
        withChild: Boolean,
        availableTasks: ImmutableList<Task> = persistentListOf(),
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
            model = TaskEditorModel(availableTasks = availableTasks),
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
