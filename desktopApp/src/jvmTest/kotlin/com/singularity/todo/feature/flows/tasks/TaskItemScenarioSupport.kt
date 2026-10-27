package com.singularity.todo.feature.flows.tasks

import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.koin.core.Koin
import kotlin.time.Instant

/**
 * Shared fixtures for the two `TASK-ITEM` scenarios.
 *
 * The traceability rule is one scenario per test file: two display-name
 * annotations carrying two scenario ids in one file are reported as two
 * scenarios claimed by one file. The scenarios therefore live in
 * `TaskItemListScenarioTest` and `TaskItemIndependenceScenarioTest`, and
 * everything they both need lives here, where no scenario is claimed.
 *
 * ## Why this screen and not the sheet
 *
 * The checklist used to be edited behind a sheet, and a sheet on desktop resolves
 * to a `Dialog` — a separate window, not an overlay in the main one. A JVM
 * Compose test cannot address anything inside it, which is why the checklist was
 * unclaimable on the desktop tier for as long as it lived there. The items are
 * inline now, so the row is a node in the app's own tree and the behaviour can
 * be asserted.
 */
internal object TaskItemScenarioSupport {

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

    /** The zone the production wiring resolves `Today` in, pinned for the test. */
    object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    suspend fun seedParent(koin: Koin): Task =
        seedTask(koin, id = PARENT_ID, title = PARENT, dueDate = todayAt(TEST_CLOCK, TEST_ZONE))

    suspend fun seedSubtask(
        koin: Koin,
        parentId: TaskId,
        title: String,
    ): Task = seedTask(
        koin = koin,
        id = TaskId("item-child"),
        title = title,
        parent = parentId,
    )

    suspend fun seedTask(
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

    suspend fun seedChecklistItem(koin: Koin, taskId: TaskId, title: String) {
        koin.get<ChecklistRepository>().addItem(taskId.value, title)
    }
}
