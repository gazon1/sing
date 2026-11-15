@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The workflow state is stored on the task and does not depend on the
 * completion flag; the dependency kind decides which relation an edge is read
 * as.
 *
 * Both are claims about what is *in the database*, so they are asserted through
 * [TaskRepositoryImpl] — the fake stores dependencies as a field of the task and
 * would agree with any implementation.
 */
@Tag("slow")
@Tag("slow:data")
@Epic("Tasks")
@Feature("Tasks")
class TaskWorkflowStateAndRelationsTest {
    private val alice = UserId("alice")

    private val fixtureLazy = lazy { DatabaseTestFixture.inMemory() }
    private val fixture: DatabaseTestFixture by fixtureLazy

    private val dbLazy =
        lazy {
            fixture.database()
        }
    private val db: AppDatabase by dbLazy

    @AfterTest
    fun tearDown() {
        if (fixtureLazy.isInitialized()) fixture.close()
    }

    private fun task(
        id: String,
        title: String = "Task $id",
    ) = Task(
        id = TaskId(id),
        title = title,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        userId = alice,
    )

    private fun repository() =
        run {
            val auth = FakeAuthRepository(Session.Anonymous(alice))
            val currentUser = FakeProfileAwareCurrentUser(auth)
            TaskRepositoryImpl(
                taskDao = db.taskDao(),
                textIndex = db.textIndex(currentUser),
                clock = kotlin.time.Clock.System,
                currentUser = currentUser,
                syncRepository = FakeSyncRepository(),
                dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
                unitOfWork = FakeUnitOfWork(),
            )
        }

    @Test
    fun `a workflow state is written by one repository and read back by a fresh one`() =
        runTest {
            repository().create(task("t1").copy(workflowState = TaskWorkflowState.InProgress))

            val loaded = repository().observe(TaskId("t1")).first()

            assertEquals(
                TaskWorkflowState.InProgress,
                loaded?.workflowState,
                "the state must be a column, not a field the instance happens to remember",
            )
        }

    @Test
    fun `the state survives completion and reopen`() =
        runTest {
            val repo = repository()
            repo.create(task("t1").copy(workflowState = TaskWorkflowState.NextUp))

            repo.complete(TaskId("t1")).getOrThrow()
            assertEquals(TaskWorkflowState.NextUp, repo.observe(TaskId("t1")).first()?.workflowState)

            repo.reopen(TaskId("t1")).getOrThrow()
            assertEquals(
                TaskWorkflowState.NextUp,
                repo.observe(TaskId("t1")).first()?.workflowState,
                "completing and reopening must not reset the state",
            )
        }

    @Test
    fun `a follow-up edge is read as follows-up and not as blocking`() =
        runTest {
            val repo = repository()
            repo.create(task("other", "Other"))
            repo.create(task("t1", "Follower"))
            repo.setDependency(TaskId("t1"), TaskId("other"), DependencyVerb.FOLLOWS_UP, enabled = true)

            val loaded = repo.observe(TaskId("t1")).first()

            assertEquals(setOf(TaskId("other")), loaded?.followsUp)
            assertTrue(
                loaded?.blockedBy.isNullOrEmpty(),
                "a follow-up is not a block: ${loaded?.blockedBy}",
            )
        }

    @Test
    fun `saving the blocking set keeps the follow-up edges`() =
        runTest {
            val repo = repository()
            repo.create(task("dep", "Dependency"))
            repo.create(task("goal", "Goal"))
            repo.create(task("t1", "Mixed"))
            repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)

            repo.setDependencies(TaskId("t1"), setOf(TaskId("goal"))).getOrThrow()

            val loaded = repo.observe(TaskId("t1")).first()
            assertEquals(setOf(TaskId("goal")), loaded?.blockedBy)
            assertEquals(
                setOf(TaskId("dep")),
                loaded?.followsUp,
                "the blocking-set write must not remove edges of another kind",
            )
        }

    /**
     * The relation map is validated before it is written.
     *
     * A task cannot block on itself, and the check runs on the same map that is about to be
     * saved — every verb of it, not just the blocking ones. A self-loop is refused with
     * nothing written; a relation that breaks no cycle is applied.
     */
    @Test
    fun `a relation map is validated before it is written`() =
        runTest {
            val repo = repository()
            repo.create(task("t1", "Cycle"))
            repo.create(task("dep", "Dependency"))

            val refused = repo.setDependencyRelations(TaskId("t1"), mapOf(TaskId("t1") to DependencyVerb.BLOCKS))

            assertTrue(refused.isFailure, "a self-loop is a cycle, and is refused: $refused")
            assertTrue(
                repo
                    .observe(TaskId("t1"))
                    .first()
                    ?.blockedBy
                    .isNullOrEmpty(),
                "and nothing is written",
            )

            repo.setDependencyRelations(TaskId("t1"), mapOf(TaskId("dep") to DependencyVerb.BLOCKS)).getOrThrow()

            assertEquals(setOf(TaskId("dep")), repo.observe(TaskId("t1")).first()?.blockedBy)
        }

    @Test
    fun `a relation map that closes a follow-up cycle is refused`() =
        runTest {
            // The map is checked as a whole: narrowing the check to its BLOCKS keys
            // would let a follow-up cycle through, and a follow-up orders the work
            // just as a block does.
            val repo = repository()
            repo.create(task("a", "A"))
            repo.create(task("b", "B"))
            repo.setDependency(TaskId("b"), TaskId("a"), DependencyVerb.FOLLOWS_UP, enabled = true)

            val refused =
                repo.setDependencyRelations(TaskId("a"), mapOf(TaskId("b") to DependencyVerb.FOLLOWS_UP))

            assertTrue(refused.isFailure, "a follow-up cycle is still a cycle: $refused")
            assertTrue(
                repo
                    .observe(TaskId("a"))
                    .first()
                    ?.followsUp
                    .isNullOrEmpty(),
                "and the closing edge is not written",
            )
        }

    @Test
    fun `disabling one verb leaves an edge of the other verb intact`() =
        runTest {
            val repo = repository()
            repo.create(task("other", "Other"))
            repo.create(task("t1", "Both"))
            repo.setDependency(TaskId("t1"), TaskId("other"), DependencyVerb.BLOCKS, enabled = true)
            repo.setDependency(TaskId("t1"), TaskId("other"), DependencyVerb.FOLLOWS_UP, enabled = true)

            repo.setDependency(TaskId("t1"), TaskId("other"), DependencyVerb.FOLLOWS_UP, enabled = false)

            val loaded = repo.observe(TaskId("t1")).first()
            assertEquals(setOf(TaskId("other")), loaded?.blockedBy)
            assertTrue(
                loaded?.followsUp.isNullOrEmpty(),
                "only the disabled verb must be gone: ${loaded?.followsUp}",
            )
        }

    // ── The single-edge write guards the ordering graph ────────────────────────
    //
    // `setDependency` is the edge-by-edge write the related-task picker goes
    // through — the sheet reports a map, the slot diffs it, and every added or
    // changed edge lands here. The picker's own search-time check is a preview:
    // it answers about the graph as it was when the row was rendered, and the
    // graph can move before Add is pressed. So the guard is asserted here, on the
    // write, where the edge meets the state it is actually joining.

    @Test
    fun `a single edge that would make a task its own dependency is refused`() =
        runTest {
            val repo = repository()
            repo.create(task("t1", "Self"))

            val refused =
                repo.setDependency(TaskId("t1"), TaskId("t1"), DependencyVerb.BLOCKS, enabled = true)

            assertTrue(refused.isFailure, "a task cannot depend on itself: $refused")
            assertTrue(
                repo
                    .observe(TaskId("t1"))
                    .first()
                    ?.blockedBy
                    .isNullOrEmpty(),
                "and the refused edge is not written",
            )
        }

    @Test
    fun `a single edge that would close a blocking cycle is refused`() =
        runTest {
            val repo = repository()
            repo.create(task("a", "A"))
            repo.create(task("b", "B"))
            repo.create(task("c", "C"))
            // C → B → A already exists; A → C would close the loop.
            repo.setDependency(TaskId("c"), TaskId("b"), DependencyVerb.BLOCKS, enabled = true)
            repo.setDependency(TaskId("b"), TaskId("a"), DependencyVerb.BLOCKS, enabled = true)

            val refused =
                repo.setDependency(TaskId("a"), TaskId("c"), DependencyVerb.BLOCKS, enabled = true)

            assertTrue(refused.isFailure, "the edge would close a cycle: $refused")
            assertTrue(
                repo
                    .observe(TaskId("a"))
                    .first()
                    ?.blockedBy
                    .isNullOrEmpty(),
                "and the closing edge is not written",
            )
        }

    @Test
    fun `a single edge that would close a follow-up cycle is refused`() =
        runTest {
            // The ordering graph is every verb. Two tasks that follow up on each
            // other are a cycle of order even though neither of them waits, so the
            // guard must not narrow to BLOCKS.
            val repo = repository()
            repo.create(task("a", "A"))
            repo.create(task("b", "B"))
            repo.setDependency(TaskId("b"), TaskId("a"), DependencyVerb.FOLLOWS_UP, enabled = true)

            val refused =
                repo.setDependency(TaskId("a"), TaskId("b"), DependencyVerb.FOLLOWS_UP, enabled = true)

            assertTrue(refused.isFailure, "a follow-up cycle is still a cycle: $refused")
            assertTrue(
                repo
                    .observe(TaskId("a"))
                    .first()
                    ?.followsUp
                    .isNullOrEmpty(),
                "and the closing edge is not written",
            )
        }

    @Test
    fun `an independent edge is written and survives a reread`() =
        runTest {
            val repo = repository()
            repo.create(task("a", "A"))
            repo.create(task("d", "Independent"))

            repo.setDependency(TaskId("a"), TaskId("d"), DependencyVerb.BLOCKS, enabled = true).getOrThrow()

            assertEquals(
                setOf(TaskId("d")),
                repo.observe(TaskId("a")).first()?.blockedBy,
                "an edge that closes no cycle must still be written",
            )
        }

    @Test
    fun `removing an edge needs no new cycle check`() =
        runTest {
            // The graph the removal leaves behind cannot contain the cycle the
            // removed edge did not have; a guard that walked the graph here would
            // refuse to undo a relation.
            val repo = repository()
            repo.create(task("a", "A"))
            repo.create(task("b", "B"))
            repo.setDependency(TaskId("a"), TaskId("b"), DependencyVerb.BLOCKS, enabled = true).getOrThrow()

            repo.setDependency(TaskId("a"), TaskId("b"), DependencyVerb.BLOCKS, enabled = false).getOrThrow()

            assertTrue(
                repo
                    .observe(TaskId("a"))
                    .first()
                    ?.blockedBy
                    .isNullOrEmpty(),
                "the removed edge is gone",
            )
        }
}
