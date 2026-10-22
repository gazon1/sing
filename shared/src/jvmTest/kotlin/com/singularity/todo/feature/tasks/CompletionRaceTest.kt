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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The window between the check and the write, and the statement that closes it.
 *
 * A check-then-write is two statements with a window between them, and the
 * window is where another device reopens the prerequisite. The check said yes,
 * the write said yes, and the task was closed while the work it was waiting for
 * had just come back.
 *
 * ## The interleaving is deliberate
 *
 * A real race is not reproducible: two writers landing in the order that hurts
 * them is a matter of timing, and a test that waits for it is a test that
 * fails on a busy machine. So the interleaving here is *written down* — the
 * check, then the other device's write, then our write — and the second
 * device's write goes through a **second connection to the same file**, so the
 * commit is a real commit made outside the pool our repository reads from.
 *
 * Two tasks are blocked by the same prerequisite, and each path gets one:
 *
 * - **t1** is closed by the write that has no condition. It lands. That is the
 *   defect, and the test asserts it — a task closed while its prerequisite is
 *   open is exactly the state the guard exists to prevent, and a test that
 *   could not produce it would not be able to show that the guard prevents it.
 * - **t2** is closed by the write that carries the condition. It refuses.
 */
@Tag("slow")
class CompletionRaceTest {

    private val alice = UserId("alice")

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-completion-race-").toFile() }
    private val tempDir: File by tempDirLazy

    private val dbPath: String by lazy { File(tempDir, "race.db").absolutePath }

    private val dbLazy = lazy {
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = dbPath,
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
    fun `a check that passed before the prerequisite came back is refused by the write`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Closed without a condition"))
        repo.create(task("t2", "Closed with a condition"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)
        repo.setDependency(TaskId("t2"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        // The prerequisite is finished, so the check says "nothing is in the way".
        repo.complete(TaskId("dep")).getOrThrow()
        val blocked = repo.blockedTaskIds(listOf(TaskId("t1"), TaskId("t2")))
        assertTrue(blocked.isEmpty(), "the check passes while the prerequisite is finished: $blocked")

        // The prerequisite comes back — on another device, on another connection.
        reopenOnAnotherConnection("dep")

        // The write without a condition: it has nothing to re-read, so it lands.
        val naive = db.taskDao().markCompleteForUser("t1", 1_000L, alice.value)
        assertEquals(1, naive, "the write that does not check lands")

        // The write with the condition: the statement re-reads the graph, so it refuses.
        val guarded = repo.complete(TaskId("t2")).getOrThrow()
        assertFalse(guarded, "the write that refuses must refuse")
        assertNull(repo.observe(TaskId("t2")).first()?.completedAt, "nothing may be written")
    }

    @Test
    fun `the state the condition prevents is the one the unconditional write leaves behind`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Closed without a condition"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        repo.complete(TaskId("dep")).getOrThrow()
        reopenOnAnotherConnection("dep")

        db.taskDao().markCompleteForUser("t1", 1_000L, alice.value)

        // The broken state, named: the task is closed and the work it waits for
        // is open again. Every later close of that prerequisite — and every
        // badge that says "blocked" — now disagrees with the task's own state.
        assertNotNull(repo.observe(TaskId("t1")).first()?.completedAt, "the task was closed")
        assertNull(repo.observe(TaskId("dep")).first()?.completedAt, "the prerequisite is open again")
    }

    /**
     * Reopens a task through a connection of its own.
     *
     * A second connection to the same file, so the commit is made outside the
     * pool our repository reads from — the same thing another device does when
     * it syncs its change into the same database.
     */
    private fun reopenOnAnotherConnection(taskId: String) {
        val connection = createSqlDriver().open(dbPath)
        connection.use {
            it.prepare(
                "UPDATE tasks SET completed_at = NULL, updated_at = 500 " +
                    "WHERE id = ? AND user_id = ?",
            ).use { statement ->
                statement.bindText(1, taskId)
                statement.bindText(2, alice.value)
                statement.step()
            }
        }
    }
}
