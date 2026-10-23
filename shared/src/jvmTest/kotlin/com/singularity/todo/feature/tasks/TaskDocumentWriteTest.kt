@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.data.parseLinksJson
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A task document is more than a row, and every path that applies one has to
 * apply all of it.
 *
 * ## Why a real database
 *
 * The parts of a task live in three places — the row, the `task_tags` cross-ref
 * table, the `task_dependencies` cross-ref table — and one column of the row
 * (`outgoing_links`) is *derived* from the description rather than carried by
 * the domain model. `@Upsert` replaces the whole row, so a writer that forgets
 * one part does not leave that part alone: it resets it. A fake that stores
 * whole `Task` objects cannot show any of this.
 *
 * ## The rule
 *
 * A document that arrives from another device is applied whole: the row, the
 * tags, the links its description implies, and the blocking dependencies it
 * carries. The paths that apply *part* of a document — an edit that changes
 * the title — are a different thing, and the tags are one of those parts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
@Tag("slow:data")
class TaskDocumentWriteTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-task-document-").toFile() }
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

    private fun repo(userId: UserId = alice): TaskRepository {
        val auth = FakeAuthRepository(Session.Anonymous(userId))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        return TaskRepositoryImpl(
            taskDao = db.taskDao(),
            textIndex = db.textIndex(currentUser),
            clock = Clock.System,
            currentUser = currentUser,
            syncRepository = FakeSyncRepository(),
            dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
            unitOfWork = FakeUnitOfWork(),
        )
    }

    private fun task(
        id: String,
        uid: UserId = alice,
        title: String = "Pay the rent",
        description: String? = null,
        tags: List<TagId> = emptyList(),
        blockedBy: Set<TaskId> = emptySet(),
        dueDate: LocalDate? = LocalDate(2026, 10, 5),
        updatedAt: Instant = Clock.System.now(),
    ) = Task(
        id = TaskId(id),
        title = title,
        description = description,
        userId = uid,
        tags = tags,
        blockedBy = blockedBy,
        dueDate = dueDate,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    private suspend fun linksOf(taskId: String, uid: UserId = alice): String? =
        db.taskDao().getByIdForUser(taskId, uid.value)?.outgoingLinks

    private suspend fun edgesOf(taskId: String, uid: UserId = alice) =
        db.taskDao().observeTypedDependenciesForUser(taskId, uid.value).first()

    private suspend fun tagsOf(taskId: String, uid: UserId = alice): List<String> =
        db.taskDao().getTagIdsForUser(taskId, uid.value).first()

    @Test
    fun `the pull path writes the tags the document carries`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        advanceUntilIdle()

        repo.upsert(repo.get(TaskId("t1"))!!.copy(tags = listOf(TagId("tag-1"))))
        advanceUntilIdle()

        assertEquals(
            listOf("tag-1"),
            tagsOf("t1"),
            "the tag of the row that arrived is in the database",
        )
    }

    @Test
    fun `the pull path replaces the tags with the ones the document carries`() = runTest {
        val repo = repo()
        repo.create(task("t1", tags = listOf(TagId("tag-1")))).getOrThrow()
        advanceUntilIdle()

        // The document that arrives carries no tag: the tags of the task are the
        // tags of the document, so the tag the document dropped is gone.
        repo.upsert(repo.get(TaskId("t1"))!!)
        advanceUntilIdle()

        assertEquals(
            emptyList(),
            tagsOf("t1"),
            "the tag the document dropped is gone",
        )
    }

    @Test
    fun `the pull path writes the links the description implies`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        advanceUntilIdle()

        // The links are derived from the description: a document that carries a
        // description carries the links that description implies.
        repo.upsert(
            repo.get(TaskId("t1"))!!.copy(description = "the details are in task://t9"),
        )
        advanceUntilIdle()

        val links = linksOf("t1")
        assertEquals(
            listOf("task://t9"),
            links.orEmpty().parseLinksJson(),
            "the links of the row that arrived are in the database",
        )
    }

    @Test
    fun `the pull path applies the dependencies the document carries`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        advanceUntilIdle()

        repo.upsert(repo.get(TaskId("t1"))!!.copy(blockedBy = setOf(TaskId("t2"))))
        advanceUntilIdle()

        val edges = edgesOf("t1")
        assertTrue(
            edges.any { it.dependsOnTaskId == "t2" && it.verb == DependencyVerb.BLOCKS.name },
            "the dependency of the row that arrived is in the database: $edges",
        )
    }

    @Test
    fun `the pull path removes a dependency the document no longer carries`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        repo.setDependencies(TaskId("t1"), setOf(TaskId("t2"))).getOrThrow()
        advanceUntilIdle()

        // The document that arrives says the task depends on nothing.
        repo.upsert(repo.get(TaskId("t1"))!!.copy(blockedBy = emptySet()))
        advanceUntilIdle()

        val edges = edgesOf("t1")
        assertTrue(
            edges.none { it.dependsOnTaskId == "t2" },
            "the dependency the document dropped is gone: $edges",
        )
    }

    @Test
    fun `the pull path leaves the edges the document never spoke about`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        // A task that follows up another one: the wire carries only the blocking
        // edges, so the document says nothing about this one.
        db.taskDao().upsertDependencyForUser(
            taskId = "t1",
            depId = "t2",
            verb = DependencyVerb.FOLLOWS_UP.name,
            userId = alice.value,
        )
        advanceUntilIdle()

        repo.upsert(repo.get(TaskId("t1"))!!.copy(blockedBy = emptySet()))
        advanceUntilIdle()

        val edges = edgesOf("t1")
        assertTrue(
            edges.any { it.dependsOnTaskId == "t2" && it.verb == DependencyVerb.FOLLOWS_UP.name },
            "the edge the document never spoke about survives: $edges",
        )
    }

    @Test
    fun `a write does not take the tags off the task`() = runTest {
        val repo = repo()
        val tag = TagId("tag-1")
        repo.create(task("t1", tags = listOf(tag))).getOrThrow()
        advanceUntilIdle()

        // The task is read the way an edit reads it: through `get()`, which does
        // not load the tags — they live in the cross-ref table, and the caller
        // that reads a task to edit it is not the caller that sets its tags.
        val edited = repo.get(TaskId("t1"))!!
        assertTrue(edited.tags.isEmpty(), "the read carries no tags — that is the point of this test")

        repo.update(edited.copy(dueDate = LocalDate(2026, 10, 6))).getOrThrow()
        advanceUntilIdle()

        assertEquals(
            listOf(tag.value),
            tagsOf("t1"),
            "the tags of the task are still the tags of the task",
        )
    }

    @Test
    fun `a document that would close a cycle still lands its row`() = runTest {
        val repo = repo()
        repo.create(task("t1")).getOrThrow()
        repo.create(task("t2")).getOrThrow()
        // t2 waits for t1 — so a document that makes t1 wait for t2 closes a circle.
        repo.setDependencies(TaskId("t2"), setOf(TaskId("t1"))).getOrThrow()
        advanceUntilIdle()

        repo.upsert(
            repo.get(TaskId("t1"))!!.copy(
                title = "Pay the rent in full",
                blockedBy = setOf(TaskId("t2")),
            ),
        )
        advanceUntilIdle()

        // The row landed: a document that says something the local graph cannot
        // hold does not stop the row from landing.
        assertEquals(
            "Pay the rent in full",
            db.taskDao().getByIdForUser("t1", alice.value)?.title,
            "the row of the document that arrived is in the database",
        )
        // And the edge that would have closed the circle was refused on its own.
        val edges = edgesOf("t1")
        assertTrue(
            edges.none { it.dependsOnTaskId == "t2" },
            "the edge that would close the cycle was refused: $edges",
        )
    }

    @Test
    fun `another profile's row keeps the links it carried`() = runTest {
        // The row of the other profile, with its links, already on this device.
        val bobRepo = repo(bob)
        bobRepo.create(task("t9", uid = bob, description = "the details are in task://t1")).getOrThrow()
        advanceUntilIdle()
        val before = linksOf("t9", bob)
        assertEquals(
            listOf("task://t1"),
            before.orEmpty().parseLinksJson(),
            "the row of the other profile carries the links its description implies",
        )

        // This device pulls that row. It is not on that profile, so it stores the
        // row and leaves the parts of the document alone — and the links the row
        // carried are the ones it keeps.
        repo().upsert(task("t9", uid = bob, description = "the details are in task://t1"))
        advanceUntilIdle()

        assertEquals(
            before,
            linksOf("t9", bob),
            "the row of the other profile kept the links it carried",
        )
    }

    @Test
    fun `another profile's row does not touch this profile's rows`() = runTest {
        val repo = repo()
        repo.create(task("t1", description = "the details are in task://t9")).getOrThrow()
        advanceUntilIdle()
        val before = linksOf("t1")

        repo.upsert(task("t9", uid = bob, description = "the details are in task://t1"))
        advanceUntilIdle()

        assertEquals(
            before,
            linksOf("t1"),
            "the row of this profile is untouched by the row of another profile",
        )
    }
}
