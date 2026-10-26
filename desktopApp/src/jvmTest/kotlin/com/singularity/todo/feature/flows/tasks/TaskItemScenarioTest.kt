package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagEnabled
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitCheckboxChecked
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickCheckbox
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Desktop carrier for `TASK-ITEM-01` and `TASK-ITEM-02` — see
 * `scripts/src/traceability/scenarios/tasks/item/`.
 *
 * The `@DisplayName`s above are the linkage. The id is the **first token** of the
 * string, and that strictness is the contract: the traceability scanner rejects
 * an id anywhere else rather than guessing.
 *
 * ## Why this screen and not the sheet
 *
 * The checklist used to be edited behind a sheet, and a sheet on desktop resolves
 * to a `Dialog` — a separate window, not an overlay in the main one. A JVM
 * Compose test cannot address anything inside it, which is why the checklist was
 * unclaimable on the desktop tier for as long as it lived there. The items are
 * inline now, so the row is a node in the app's own tree and the behaviour can
 * be asserted.
 *
 * ## What is asserted, and what is not
 *
 * The assertions are on what the user reads — a checkbox that is really checked,
 * a count that really follows — and on the state the production wiring writes:
 * the task's own completion flag is read back from the repository, because
 * "ticking a checklist item does not complete the task" is a claim about a
 * write that did not happen, not about a pixel.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskItemScenarioTest {

    @Test
    @DisplayName("TASK-ITEM-01 the task screen lists both kinds of item with checkboxes")
    fun the_task_screen_lists_both_kinds_of_item_with_checkboxes() = runDesktopAppTest(
        clock = TEST_CLOCK,
        overrides = module { single<TimeZoneProvider> { TestTimeZone } },
    ) { koin ->
        val parent = seedParent(koin)
        seedChecklistItem(koin, parent.id, MILK)
        seedChecklistItem(koin, parent.id, BREAD)
        seedSubtask(koin, parent.id, CHILD)

        tapTab("Today")
        awaitTag(TestTags.taskItem(PARENT)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        // Both lists are on the task screen, each with a checkbox per row. The
        // checklist section renders even though the list was empty a moment ago
        // — it is where the add-row lives, so a section that hid itself would
        // leave no way to add the first item.
        scrollToTag(TestTags.TASK_CHECKLIST_SECTION)
        assertTagDisplayed(TestTags.TASK_CHECKLIST_SECTION)

        scrollToTag(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        assertTagDisplayed(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        assertTagDisplayed(TestTags.checklistItemCheckbox(id = BREAD, title = BREAD))

        // The counts are the finished items out of all of them, one number per
        // list.
        assertTextDisplayed("Checklist (0/2)")

        scrollToTag(TestTags.subtaskCheckbox(CHILD))
        assertTagDisplayed(TestTags.SUBTASKS_SECTION)
        assertTagDisplayed(TestTags.subtaskCheckbox(CHILD))
        assertTextDisplayed("Subtasks (0/1)")

        // Ticking a checklist item ticks that item, and the count follows.
        scrollToTag(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        clickCheckbox(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        awaitCheckboxChecked(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        awaitText("Checklist (1/2)")

        // An item is added from the screen itself: the add-row is inside the
        // section, and nothing opens over the task while it happens.
        scrollToTag(TestTags.CHECKLIST_INLINE_ADD_INPUT)
        typeIntoTag(TestTags.CHECKLIST_INLINE_ADD_INPUT, EGGS)
        assertTagEnabled(TestTags.CHECKLIST_INLINE_ADD_BUTTON)
        clickTag(TestTags.CHECKLIST_INLINE_ADD_BUTTON)
        awaitTag(TestTags.checklistItem(id = EGGS, title = EGGS))
        scrollToTag(TestTags.checklistItem(id = EGGS, title = EGGS))
        assertTagDisplayed(TestTags.checklistItem(id = EGGS, title = EGGS))

        // The checklist sheet is still a thing this app has, and it is not what
        // the add-row opened.
        assertTagNotExists(TestTags.CHECKLIST_SHEET)

        awaitText("Checklist (1/3)")
    }

    @Test
    @DisplayName("TASK-ITEM-02 a checklist item and a subtask do not change each other")
    fun a_checklist_item_and_a_subtask_do_not_change_each_other() = runDesktopAppTest(
        clock = TEST_CLOCK,
        overrides = module { single<TimeZoneProvider> { TestTimeZone } },
    ) { koin ->
        val parent = seedParent(koin)
        seedChecklistItem(koin, parent.id, MILK)
        val child = seedSubtask(koin, parent.id, CHILD)

        tapTab("Today")
        awaitTag(TestTags.taskItem(PARENT)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        // Ticking the checklist item touches the checklist item and nothing else.
        scrollToTag(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        clickCheckbox(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        awaitCheckboxChecked(TestTags.checklistItemCheckbox(id = MILK, title = MILK))

        assertFalse(
            isChecked(TestTags.subtaskCheckbox(CHILD)),
            "the subtask must not be finished because a checklist item was ticked",
        )
        assertFalse(
            koin.get<TaskRepository>().get(parent.id)?.isCompleted == true,
            "the task must not be finished because a checklist item was ticked",
        )

        // Ticking the subtask finishes the subtask — and only the subtask. The
        // checklist keeps the state it had, and the task stays unfinished: a
        // task with all of its parts done is not a finished task.
        scrollToTag(TestTags.subtaskCheckbox(CHILD))
        clickCheckbox(TestTags.subtaskCheckbox(CHILD))
        awaitCheckboxChecked(TestTags.subtaskCheckbox(CHILD))

        assertTrue(
            isChecked(TestTags.checklistItemCheckbox(id = MILK, title = MILK)),
            "the checklist item must keep its state because a subtask was ticked",
        )
        assertFalse(
            koin.get<TaskRepository>().get(parent.id)?.isCompleted == true,
            "the task must not be finished because its subtask was finished",
        )

        // The subtask itself went through the task lifecycle, which is a
        // different write from a checklist toggle.
        assertTrue(
            koin.get<TaskRepository>().get(child.id)?.isCompleted == true,
            "the subtask must be finished — it was ticked",
        )
    }

    /**
     * Whether the checkbox with [tag] reports itself as on.
     *
     * The repository is asked about the *task* rather than about a pixel, but a
     * checkbox has no "is ticked" property on the node — its state is the
     * `ToggleableState` the toggleable modifier writes. This is the same
     * question [awaitCheckboxChecked] asks, with the other answer.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.DesktopComposeUiTest.isChecked(tag: String): Boolean =
        onAllNodesWithTag(tag).fetchSemanticsNodes().any { node ->
            node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
        }

    private suspend fun seedParent(koin: Koin): Task =
        seedTask(koin, id = PARENT_ID, title = PARENT, dueDate = todayAt(TEST_CLOCK, TEST_ZONE))

    private suspend fun seedSubtask(
        koin: Koin,
        parentId: TaskId,
        title: String,
    ): Task = seedTask(
        koin = koin,
        id = TaskId("item-child"),
        title = title,
        parent = parentId,
    )

    private suspend fun seedTask(
        koin: Koin,
        id: TaskId,
        title: String,
        dueDate: LocalDate? = null,
        parent: TaskId? = null,
    ): Task {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        // `testTask`'s `overrides` is a receiver lambda whose return value is
        // ignored: `copy(...)` inside it would build a task and throw it away.
        // Build the copy here instead.
        val task = testTask(
            id = id,
            title = title,
            dueDate = dueDate,
            userId = userId,
            createdAt = EPOCH,
            updatedAt = EPOCH,
        ).copy(parentTaskId = parent)
        koin.get<TaskRepository>().upsert(TaskDocument.of(task))
        return task
    }

    private suspend fun seedChecklistItem(koin: Koin, taskId: TaskId, title: String) {
        koin.get<ChecklistRepository>().addItem(taskId.value, title)
    }

    private object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    private companion object {
        const val PARENT = "Parent task"
        const val MILK = "Milk"
        const val BREAD = "Bread"
        const val EGGS = "Eggs"
        const val CHILD = "Child task"

        val PARENT_ID = TaskId("item-parent")
        val TEST_ZONE: TimeZone = TimeZone.UTC
        val EPOCH: Instant = Instant.fromEpochMilliseconds(0)

        /**
         * Midday UTC on a Saturday, so no timezone that can reach this test can
         * roll the date across a midnight boundary and empty the Today tab.
         */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
    }
}
