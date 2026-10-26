@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
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
class TaskWorkflowStateAndRelationsTest {

    private val alice = UserId("alice")

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-state-rel-").toFile() }
    private val tempDir: File by tempDirLazy

    private val dbLazy = lazy {
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = File(tempDir, "tasks.db").absolutePath,
        )
    }
    private val db: AppDatabase by dbLazy

    @AfterTest
    fun tearDown() {
        if (dbLazy.isInitialized()) db.close()
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    private fun task(id: String, title: String = "Task $id") = Task(
        id = TaskId(id),
        title = title,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        userId = alice,
    )

    private fun repository() = run {
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
    fun `a workflow state is written by one repository and read back by a fresh one`() = runTest {
        repository().create(task("t1").copy(workflowState = TaskWorkflowState.InProgress))

        val loaded = repository().observe(TaskId("t1")).first()

        assertEquals(
            TaskWorkflowState.InProgress,
            loaded?.workflowState,
            "the state must be a column, not a field the instance happens to remember",
        )
    }

    @Test
    fun `the state survives completion and reopen`() = runTest {
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
    fun `a follow-up edge is read as follows-up and not as blocking`() = runTest {
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
    fun `saving the blocking set keeps the follow-up edges`() = runTest {
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
     * saved — the blocking subset of it, as identifiers. A self-loop is refused with nothing
     * written; a relation that breaks no cycle is applied.
     */
    @Test
    fun `a relation map is validated before it is written`() = runTest {
        val repo = repository()
        repo.create(task("t1", "Cycle"))
        repo.create(task("dep", "Dependency"))

        val refused = repo.setDependencyRelations(TaskId("t1"), mapOf(TaskId("t1") to DependencyVerb.BLOCKS))

        assertTrue(refused.isFailure, "a self-loop is a cycle, and is refused: $refused")
        assertTrue(
            repo.observe(TaskId("t1")).first()?.blockedBy.isNullOrEmpty(),
            "and nothing is written",
        )

        repo.setDependencyRelations(TaskId("t1"), mapOf(TaskId("dep") to DependencyVerb.BLOCKS)).getOrThrow()

        assertEquals(setOf(TaskId("dep")), repo.observe(TaskId("t1")).first()?.blockedBy)
    }

    @Test
    fun `disabling one verb leaves an edge of the other verb intact`() = runTest {
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
}
