package com.singularity.todo.feature.tasks

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitAnyDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTagFirst
import com.singularity.todo.test.helpers.longClickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.semanticsRoots
import com.singularity.todo.test.helpers.visit
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The two presentations of [TaskCard], which issue #2739 split apart.
 *
 * A pointer layout keeps the trailing pin/delete icon buttons. A touch layout
 * draws neither — the row is content and one tap target — and reaches the same
 * actions through a long press. The second test drives that touch layout on the
 * desktop harness, so the difference is asserted on both sides: the rendered
 * icons in the first test, their absence and the menu in the second. The
 * Android wiring itself (`touchLayout = isAndroidPlatform`) is covered end to
 * end by the Maestro flows for project detail and search.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskCardTouchPresentationTest {
    @Test
    fun `pointer layout draws the trailing actions`() =
        runIsolatedComposeTest {
            setContent {
                SingularityTheme(themeMode = ThemeMode.Light) {
                    TaskCard(
                        task = task(),
                        onClick = {},
                        actions = TaskCardActions(onPin = noop, onDelete = noop),
                    )
                }
            }

            assertTagDisplayed(TestTags.TASK_CARD_PIN_BUTTON)
            assertTagDisplayed(TestTags.TASK_CARD_DELETE_BUTTON)
        }

    @Test
    fun `touch layout hides the trailing actions and offers them on long press`() =
        runIsolatedComposeTest {
            var pinCalls = 0
            var deleteCalls = 0
            setContent {
                SingularityTheme(themeMode = ThemeMode.Light) {
                    TaskCard(
                        task = task(),
                        onClick = {},
                        actions =
                            TaskCardActions(
                                onPin = { pinCalls++ },
                                onDelete = { deleteCalls++ },
                            ),
                        touchLayout = true,
                    )
                }
            }

            assertTagNotExists(TestTags.TASK_CARD_PIN_BUTTON)
            assertTagNotExists(TestTags.TASK_CARD_DELETE_BUTTON)

            // The screen-reader route: the same two callbacks, without a long press.
            val announced = announcedCustomActions()
            assertTrue(
                announced.map { it.label }.containsAll(listOf("Pin", "Delete")),
                "a screen reader reaches the actions without the long press: ${announced.map { it.label }}",
            )
            assertTrue(announced.first { it.label == "Pin" }.action(), "the custom action reports handled")
            assertEquals(1, pinCalls, "the screen-reader pin row runs the card's own pin callback")
            assertEquals(0, deleteCalls, "the pin row is not the delete row")
            assertTrue(announced.first { it.label == "Delete" }.action(), "the custom action reports handled")
            assertEquals(1, deleteCalls, "the screen-reader row runs the card's own delete callback")

            // The touch route. Rows are addressed by tag: a desktop popup is in
            // the tree twice while it animates, and a text selector refuses to
            // choose between the copies.
            longClickTag(TestTags.taskItem(task().title))
            awaitAnyDisplayed(TestTags.TASK_CARD_ACTION_PIN)
            awaitAnyDisplayed(TestTags.TASK_CARD_ACTION_DELETE)

            clickTagFirst(TestTags.TASK_CARD_ACTION_PIN)
            awaitTagGone(TestTags.TASK_CARD_ACTION_PIN)
            assertEquals(2, pinCalls, "the long-press row runs the card's own pin callback")

            longClickTag(TestTags.taskItem(task().title))
            awaitAnyDisplayed(TestTags.TASK_CARD_ACTION_DELETE)
            clickTagFirst(TestTags.TASK_CARD_ACTION_DELETE)
            assertEquals(2, deleteCalls, "the long-press delete row runs the card's own delete callback")
        }

    /** Every accessibility custom action the scene announces, across all roots. */
    private fun DesktopComposeUiTest.announcedCustomActions(): List<CustomAccessibilityAction> =
        buildList {
            for (root in semanticsRoots()) {
                root.visit { node ->
                    node.config.getOrNull(SemanticsActions.CustomActions)?.let(::addAll)
                }
            }
        }

    private fun task(): Task =
        Task(
            id = TaskId("t-task-card"),
            title = "Buy milk",
            createdAt = Instant.fromEpochMilliseconds(1_760_000_000_000),
            updatedAt = Instant.fromEpochMilliseconds(1_760_000_000_000),
            userId = UserId("u1"),
        )

    /** A named no-op, not `onPin = {}` — the empty-handler rule reads call sites. */
    private val noop: () -> Unit = {}
}
