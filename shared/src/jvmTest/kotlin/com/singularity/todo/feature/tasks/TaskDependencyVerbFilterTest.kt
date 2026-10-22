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
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
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
import kotlin.test.assertTrue

/**
 * The dependency set a task carries is the set of the edges that **block** it.
 *
 * The cross-reference table records the kind of every relationship. A task that
 * "follows up" on another is not waiting for it, and reporting it as waiting is
 * what this filter fixes.
 *
 * The filter lives in the repository rather than in the SQL because the same table
 * is read by the cycle analysis, the backup export and the sync payload, and all
 * three need *every* edge. So the narrowed read is asserted here — against the real
 * database, where the join table actually is.
 *
 * The fake cannot answer this: it stores the task's dependency set as a field of the
 * task rather than resolving it from the join table, so there is no kind to narrow.
 */
@Tag("slow")
class TaskDependencyVerbFilterTest {

    private val alice = UserId("alice")

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-dep-verb-").toFile() }
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
    fun `a blocking edge is in the task's dependency set`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Blocked"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        val loaded = repo.observe(TaskId("t1")).first()

        assertEquals(setOf(TaskId("dep")), loaded?.dependsOn)
    }

    @Test
    fun `an edge of another kind is not in the task's dependency set`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Follower"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)

        val loaded = repo.observe(TaskId("t1")).first()

        assertTrue(
            loaded?.dependsOn.isNullOrEmpty(),
            "a task that follows up on another is not waiting for it: ${loaded?.dependsOn}",
        )
    }

    @Test
    fun `the list read narrows the same way as the single-task read`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Follower"))
        repo.create(task("t2", "Blocked"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)
        repo.setDependency(TaskId("t2"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        val listed = repo.observeByFilter(TaskFilter.All).first().associateBy { it.id }

        assertTrue(listed[TaskId("t1")]?.dependsOn.isNullOrEmpty())
        assertEquals(setOf(TaskId("dep")), listed[TaskId("t2")]?.dependsOn)
    }

    @Test
    fun `the typed read still sees every edge`() = runTest {
        val repo = repository()
        repo.create(task("dep", "Dependency"))
        repo.create(task("t1", "Follower"))
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)

        val typed = repo.observeTypedDependencies(TaskId("t1")).first()

        assertEquals(1, typed.size, "the graph keeps every edge: ${typed.map { it.verb }}")
        assertEquals(DependencyVerb.FOLLOWS_UP, typed.single().verb)
    }
}
