@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.fakes

import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fake DAO answers `blockingTasksForUser` the way the SQL does — on both rows.
 *
 * ## The defect this pins
 *
 * The Room query scopes the task that holds the edge **and** the prerequisite it
 * names. The fake originally checked only the prerequisite, so it answered for a
 * task another profile owned — an edge alice's row holds into bob's row came back
 * from alice's read. The contract tests run through `TaskRepositoryImpl.openBlockersOf`,
 * which reads the current user first, so they could not see the difference.
 *
 * That matters beyond test tidiness: `FakeAppDatabase` is the database the desktop
 * Compose tests mount, so a fake that answers a scoping question differently from
 * Room hides exactly the cross-profile bugs the scoping exists to prevent.
 */
@Tag("fast")
@Epic("Testing")
@Feature("Fakes")
class FakeTaskDaoBlockingScopeTest {
    private val alice = "alice"
    private val bob = "bob"

    private fun task(
        id: String,
        owner: String,
        completed: Long? = null,
    ) = TaskEntity(
        id = id,
        title = "Task $id",
        description = null,
        projectId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        completedAt = completed,
        archivedAt = null,
        createdAt = 0,
        updatedAt = 0,
        userId = owner,
    )

    private fun database() = FakeAppDatabase()

    @Test
    fun `an open prerequisite of the owner's task blocks it`() =
        runTest {
            val db = database()
            db.seedTasks(listOf(task("t1", alice), task("dep", alice)))
            db.taskDao().upsertDependency(TaskDependencyCrossRef("t1", "dep", DependencyVerb.BLOCKS.name))

            assertEquals(
                listOf("dep"),
                db
                    .taskDao()
                    .blockingTasksForUser("t1", alice, DependencyVerb.BLOCKS.name)
                    .map { it.id },
            )
        }

    @Test
    fun `a prerequisite another profile owns does not block`() =
        runTest {
            val db = database()
            // A sync can carry this shape in: alice's row, an edge into bob's row.
            db.seedTasks(listOf(task("t1", alice), task("bob-dep", bob)))
            db.taskDao().upsertDependency(TaskDependencyCrossRef("t1", "bob-dep", DependencyVerb.BLOCKS.name))

            assertTrue(
                db
                    .taskDao()
                    .blockingTasksForUser("t1", alice, DependencyVerb.BLOCKS.name)
                    .isEmpty(),
                "another profile's row is not this profile's blocker",
            )
        }

    @Test
    fun `a task another profile owns has no blockers for this profile`() =
        runTest {
            val db = database()
            // The shape that separates the two scopes: bob owns the task that holds
            // the edge, alice owns the prerequisite. A fake that checks only the
            // prerequisite answers alice with a blocker for a task she does not own.
            db.seedTasks(listOf(task("t1", bob), task("dep", alice)))
            db.taskDao().upsertDependency(TaskDependencyCrossRef("t1", "dep", DependencyVerb.BLOCKS.name))

            assertTrue(
                db
                    .taskDao()
                    .blockingTasksForUser("t1", alice, DependencyVerb.BLOCKS.name)
                    .isEmpty(),
                "a task this profile does not own is not a task it can be blocked on",
            )
        }

    @Test
    fun `a finished prerequisite does not block`() =
        runTest {
            val db = database()
            db.seedTasks(listOf(task("t1", alice), task("dep", alice, completed = 1_000)))
            db.taskDao().upsertDependency(TaskDependencyCrossRef("t1", "dep", DependencyVerb.BLOCKS.name))

            assertTrue(
                db
                    .taskDao()
                    .blockingTasksForUser("t1", alice, DependencyVerb.BLOCKS.name)
                    .isEmpty(),
                "a finished prerequisite is not in the way",
            )
        }

    @Test
    fun `a follow-up edge is not a blocker`() =
        runTest {
            val db = database()
            db.seedTasks(listOf(task("t1", alice), task("dep", alice)))
            db.taskDao().upsertDependency(TaskDependencyCrossRef("t1", "dep", DependencyVerb.FOLLOWS_UP.name))

            assertTrue(
                db
                    .taskDao()
                    .blockingTasksForUser("t1", alice, DependencyVerb.BLOCKS.name)
                    .isEmpty(),
                "following up on a task is not waiting for it",
            )
        }
}
