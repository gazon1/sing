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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The refusal lives in the statement, not in a check that runs before it.
 *
 * A check-then-write is two statements with a window between them, and the
 * window is where another device reopens the prerequisite: the check said yes,
 * the write said yes, and the task was closed while the work it was waiting for
 * had just come back. So the condition is inside the UPDATE, and the write is
 * what refuses.
 *
 * The fake cannot answer this — it keeps the dependency set as a field of the
 * task and computes the answer in Kotlin. This is asserted against the real
 * database, where the join table and the statement are.
 */
@Tag("slow")
class BlockedCompletionWriteTest {

    private val alice = UserId("alice")

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-blocked-write-").toFile() }
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
    fun `the write refuses while a prerequisite is open`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Blocked"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        val completed = repo.complete(TaskId("t1")).getOrThrow()

        assertFalse(completed, "the statement must refuse the completion")
        assertNull(repo.observe(TaskId("t1")).first()?.completedAt, "nothing may be written")
    }

    @Test
    fun `a forced write goes through`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Blocked"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        val completed = repo.complete(TaskId("t1"), force = true).getOrThrow()

        assertTrue(completed, "the confirmed completion must land")
        assertEquals(true, repo.observe(TaskId("t1")).first()?.completedAt != null)
    }

    @Test
    fun `a prerequisite that is finished no longer blocks the write`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Blocked"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)
        repo.complete(TaskId("dep")).getOrThrow()

        val completed = repo.complete(TaskId("t1")).getOrThrow()

        assertTrue(completed, "the work it waited for is done")
    }

    @Test
    fun `an edge of another kind does not block the write`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Follower"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)

        val completed = repo.complete(TaskId("t1")).getOrThrow()

        assertTrue(completed, "a task that follows up on another is not waiting for it")
    }

    @Test
    fun `the blocked-task read names only the tasks that are blocked`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("blocked", "Blocked"))
        repo.create(task("follower", "Follower"))
        repo.create(task("free", "Free"))
        repo.setDependency(TaskId("blocked"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)
        repo.setDependency(TaskId("follower"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)

        val blocked = repo.blockedTaskIds(
            listOf(TaskId("blocked"), TaskId("follower"), TaskId("free")),
        )

        assertEquals(setOf(TaskId("blocked")), blocked)
    }

    @Test
    fun `a finished prerequisite is not a blocker`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Blocked"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)
        repo.complete(TaskId("dep")).getOrThrow()

        val blocked = repo.blockedTaskIds(listOf(TaskId("t1")))

        assertTrue(blocked.isEmpty(), "the prerequisite is done: $blocked")
    }

    @Test
    fun `reopening a task is never refused`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Blocked"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        repo.reopen(TaskId("t1")).getOrThrow()

        assertNull(repo.observe(TaskId("t1")).first()?.completedAt)
    }
}
