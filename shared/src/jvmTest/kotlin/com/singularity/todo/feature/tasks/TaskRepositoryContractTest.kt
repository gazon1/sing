@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that [TaskRepository] implementations satisfy the same contract,
 * regardless of whether they are backed by an in-memory fake or a real
 * SQLite database via Room.
 *
 * Both [FakeTaskRepositoryContractTest] and [RoomTaskRepositoryContractTest]
 * extend this class. JUnit discovers the `@Test` methods from the parent and
 * runs them against each subclass's `newRepository()`.
 *
 * ## Motivation
 *
 * `FakeTaskRepository` was the original test double for the data layer.
 * Several bugs were found and fixed in it (user scoping, soft-delete vs hard-delete,
 * read-before-write ownership checks — see [FakeRepositoryFidelityTest]). The fixes
 * were written against `FakeTaskRepository`, raising the question: does a real
 * `TaskRepositoryImpl` on Room behave the same way?
 *
 * ## Adding scenarios
 *
 * 1. Add a regular `@Test` method to this class.
 * 2. Both implementations will run it automatically (JUnit inheritance).
 * 3. Keep assertions on the `TaskRepository` interface only — no DAO-level access.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class TaskRepositoryContractTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")

    /** Creates a repository scoped to [userId]. Called once per test by the base class. */
    protected abstract suspend fun newRepository(userId: UserId): TaskRepository

    private fun task(id: String, uid: UserId = alice, title: String = "T"): Task = Task(
        id = TaskId(id),
        title = title,
        userId = uid,
        createdAt = kotlin.time.Clock.System.now(),
        updatedAt = kotlin.time.Clock.System.now(),
    )

    // ─── Creation ───────────────────────────────────────────────────────────────

    @Test
    fun `create emits the task through observeAll`() = runTest {
        val repo = newRepository(alice)
        repo.create(task("t1"))
        advanceUntilIdle()
        assertTrue(repo.observeAll().first().any { it.id.value == "t1" })
    }

    @Test
    fun `create returns the created task`() = runTest {
        val repo = newRepository(alice)
        val result = repo.create(task("t1", title = "Buy milk"))
        assertTrue(result.isSuccess)
        assertEquals("Buy milk", result.getOrNull()?.title)
    }

    // ─── User scoping ───────────────────────────────────────────────────────────

    @Test
    fun `observeAll returns only the current user's tasks`() = runTest {
        val aliceRepo = newRepository(alice)
        aliceRepo.create(task("t1", alice))
        aliceRepo.create(task("t2", bob)) // bob's task — must not appear
        advanceUntilIdle()
        val aliceTasks = aliceRepo.observeAll().first()
        assertTrue(aliceTasks.all { it.userId == alice }, "observeAll must be user-scoped")
        assertEquals(1, aliceTasks.size)
    }

    @Test
    fun `get returns null for another user's task`() = runTest {
        val aliceRepo = newRepository(alice)
        aliceRepo.create(task("t1", bob))
        advanceUntilIdle()
        // The task exists in the DB but belongs to bob — alice's user-scoped get returns null
        assertNull(aliceRepo.get(TaskId("t1")))
    }

    // ─── Update ─────────────────────────────────────────────────────────────────

    @Test
    fun `update on a non-existent task fails`() = runTest {
        val repo = newRepository(alice)
        val result = repo.update(task("nonexistent", alice))
        assertTrue(result.isFailure)
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `update on another user's task fails`() = runTest {
        val aliceRepo = newRepository(alice)
        aliceRepo.create(task("t1", bob))
        advanceUntilIdle()
        val result = aliceRepo.update(task("t1", bob).copy(title = "hijacked"))
        assertTrue(result.isFailure, "update must fail for foreign task")
    }

    @Test
    fun `update changes the title`() = runTest {
        val repo = newRepository(alice)
        repo.create(task("t1"))
        advanceUntilIdle()
        repo.update(task("t1").copy(title = "Updated title"))
        advanceUntilIdle()
        val t1 = repo.observeAll().first().first { it.id.value == "t1" }
        assertEquals("Updated title", t1.title)
    }

    // ─── Soft delete ────────────────────────────────────────────────────────────

    @Test
    fun `delete is soft — the row survives in observeByFilter Trash`() = runTest {
        val repo = newRepository(alice)
        repo.create(task("t1"))
        advanceUntilIdle()
        repo.delete(TaskId("t1"))
        advanceUntilIdle()
        // Soft-delete sets archivedAt; the row survives in the database and appears in Trash
        assertTrue(repo.observeByFilter(TaskFilter.Trash).first().any { it.id.value == "t1" })
    }

    // ─── Complete ───────────────────────────────────────────────────────────────

    @Test
    fun `toggleComplete flips completed_at`() = runTest {
        val repo = newRepository(alice)
        repo.create(task("t1"))
        advanceUntilIdle()
        repo.toggleComplete(TaskId("t1"))
        advanceUntilIdle()
        val t1 = repo.observeAll().first().first { it.id.value == "t1" }
        assertNotNull(t1.completedAt)
    }

    @Test
    fun `toggleComplete twice clears completed_at`() = runTest {
        val repo = newRepository(alice)
        repo.create(task("t1"))
        advanceUntilIdle()
        repo.toggleComplete(TaskId("t1"))
        advanceUntilIdle()
        repo.toggleComplete(TaskId("t1"))
        advanceUntilIdle()
        val t1 = repo.observeAll().first().first { it.id.value == "t1" }
        assertNull(t1.completedAt)
    }

    // ─── Filter ────────────────────────────────────────────────────────────────

    @Test
    fun `observeByFilter All returns all non-archived tasks`() = runTest {
        val repo = newRepository(alice)
        repo.create(task("t1"))
        repo.create(task("t2"))
        advanceUntilIdle()
        val tasks = repo.observeByFilter(TaskFilter.All).first()
        assertEquals(2, tasks.size)
    }

    /**
     * Regression: tasks with `dueDate = null` must be returned by `observeByFilter(All)`,
     * alongside dated tasks. Before the fix in [ProfileAwareCurrentUser] the seeded
     * `_scopedUserId` was `MutableStateFlow(UserId.anonymous)` (not yet set from the
     * auth context), causing repository queries to return no results for the real user.
     */
    @Test
    fun `observeByFilter All returns an undated task alongside dated ones`() = runTest {
        val repo = newRepository(alice)
        repo.create(task("dated-1").copy(dueDate = LocalDate(2026, 1, 1)))
        repo.create(task("undated-1")) // dueDate = null
        advanceUntilIdle()
        val tasks = repo.observeByFilter(TaskFilter.All).first()
        assertEquals(2, tasks.size)
        assertEquals(
            "undated-1",
            tasks.first { it.id.value == "undated-1" }.id.value,
        )
        assertEquals(
            null,
            tasks.first { it.id.value == "undated-1" }.dueDate,
        )
    }

    // ─── Exists ────────────────────────────────────────────────────────────────

    @Test
    fun `exists returns false for unknown id`() = runTest {
        val repo = newRepository(alice)
        assertFalse(repo.exists(TaskId("unknown")))
    }

    @Test
    fun `exists returns true for a created task`() = runTest {
        val repo = newRepository(alice)
        repo.create(task("t1"))
        advanceUntilIdle()
        assertTrue(repo.exists(TaskId("t1")))
    }
}

// ─── Fake implementation ────────────────────────────────────────────────────────

/**
 * Runs the full [TaskRepositoryContractTest] suite against [FakeTaskRepository].
 *
 * Fast (~100 ms): no SQLite, no I/O.
 */
class FakeTaskRepositoryContractTest : TaskRepositoryContractTest() {
    override suspend fun newRepository(userId: UserId): TaskRepository {
        val auth = FakeAuthRepository(Session.Anonymous(userId))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        return FakeTaskRepository(explicitCurrentUser = currentUser)
    }
}

// ─── Room implementation ────────────────────────────────────────────────────────

/**
 * Runs the full [TaskRepositoryContractTest] suite against a real [TaskRepositoryImpl]
 * backed by an in-memory `BundledSQLiteDriver`.
 *
 * Slow (~2-3 s): spins up Room, creates schema, runs SQL.
 * Run explicitly with `./gradlew :shared:jvmTest -Ptest.tags=slow`, or include in
 * CI with `--all`.
 */
@Tag("slow")
class RoomTaskRepositoryContractTest : TaskRepositoryContractTest() {

    // One in-memory database per test class — shared across all test methods.
    // The class is instantiated once per test class by JUnit; forkEvery=1 (set in
    // shared/build.gradle.kts) ensures no state leaks between classes.
    private val db: AppDatabase by lazy {
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = ":memory:",
        )
    }

    override suspend fun newRepository(userId: UserId): TaskRepository {
        val auth = FakeAuthRepository(Session.Anonymous(userId))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        val syncRepo: SyncRepository = FakeSyncRepository()
        return TaskRepositoryImpl(
            taskDao = db.taskDao(),
            clock = kotlin.time.Clock.System,
            currentUser = currentUser,
            syncRepository = syncRepo,
            dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
        )
    }
}
