@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.profile

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.data.SavedAgendaViewsRepositoryImpl
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.data.NotesRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.data.ProjectsRepositoryImpl
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The profile wipe behind the debug/E2E `reset=content` link, against real SQLite.
 *
 * ## What this pins
 *
 * The wipe has to leave the profile *empty*, not merely free of visible rows. A
 * sweep of the live rows cannot do that: `observeAll` reports the live rows
 * only, so every archived, trashed and soft-deleted row survives the sweep and
 * shows up in the next run's trash — the previous run's state, seen by the next
 * one. The wipe therefore deletes by owner, in one statement per table, and
 * this test says so against the real SQL rather than against a fake that
 * re-implements it.
 *
 * ## Why a real database
 *
 * A fake DAO would only prove that the fake deletes what the fake deletes. The
 * defect this operation fixes is a *query* that does not reach the archived
 * rows, and only real SQLite can show that.
 *
 * ## The scope
 *
 * The wipe is scoped to the active profile and reaches nothing else: another
 * profile's rows survive it, because the scope is the profile-scoped user id
 * and every row of the profile carries it.
 */
@Tag("slow")
class ProfileContentWipeTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-wipe-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    // ─── Tasks ──────────────────────────────────────────────────────────────────

    @Test
    fun `the wipe removes the archived tasks of the profile, not only the live ones`() = runTest {
        val repo = taskRepo(alice)
        repo.create(task("t1"))
        repo.create(task("t2"))
        // The row a live sweep never sees: archived, and therefore invisible to
        // observeAll.
        repo.softDelete(TaskId("t2"))

        val removed = repo.deleteAllForCurrentProfile().getOrThrow()

        assertEquals(2, removed, "both the live and the archived task belong to the profile")
        assertTrue(
            db.taskDao().listAllForUser(alice.value).isEmpty(),
            "the profile must hold no task rows at all — the trash included",
        )
    }

    @Test
    fun `the wipe leaves another profile's tasks alone`() = runTest {
        taskRepo(alice).create(task("t1"))
        taskRepo(bob).create(task("b1", bob))

        taskRepo(alice).deleteAllForCurrentProfile()

        assertEquals(
            listOf("b1"),
            db.taskDao().listAllForUser(bob.value).map { it.id },
            "the wipe must not reach another profile's rows",
        )
    }

    @Test
    fun `the wipe takes the cross-references of the removed tasks with them`() = runTest {
        val repo = taskRepo(alice)
        repo.create(task("t1").copy(tags = listOf(TagId("tag1"))))
        repo.create(task("t2"))
        repo.setDependencies(TaskId("t2"), setOf(TaskId("t1")))
        db.checklistDao().upsert(
            ChecklistItemEntity(
                id = "c1",
                taskId = "t1",
                title = "Subtask",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

        repo.deleteAllForCurrentProfile()

        assertTrue(
            db.taskDao().listAllTagsForUser(alice.value).isEmpty(),
            "the tag references of a removed task are rows of their own table",
        )
        assertTrue(
            db.taskDao().listAllDependenciesForUser(alice.value).isEmpty(),
            "the dependency edges of a removed task are rows of their own table",
        )
        assertTrue(
            db.checklistDao().listAllForUser(alice.value).isEmpty(),
            "the checklist items of a removed task are rows of their own table",
        )
    }

    @Test
    fun `the wipe tells the server the rows are gone`() = runTest {
        val sync = FakeSyncRepository()
        val repo = taskRepo(alice, sync)
        repo.create(task("t1"))

        repo.deleteAllForCurrentProfile()

        val enqueued = sync.enqueuedEntities.filterIsInstance<Task>()
        assertEquals(2, enqueued.size, "the create and the wipe each push the task")
        assertNotNull(
            enqueued.last().archivedAt,
            "the delete travels as state: the server has to learn the row is gone",
        )
    }

    // ─── Notes ──────────────────────────────────────────────────────────────────

    @Test
    fun `the wipe removes the deleted notes of the profile, not only the live ones`() = runTest {
        val repo = notesRepo(alice)
        repo.createWithContent(NoteId("n1"), "Live", "", "")
        repo.createWithContent(NoteId("n2"), "Deleted", "", "")
        repo.delete(NoteId("n2"))

        val removed = repo.deleteAllForCurrentProfile().getOrThrow()

        assertEquals(2, removed, "both the live and the deleted note belong to the profile")
        assertTrue(
            db.noteDao().listAllForUser(alice.value).isEmpty(),
            "the profile must hold no note rows at all — the deleted ones included",
        )
    }

    @Test
    fun `the wipe leaves another profile's notes alone`() = runTest {
        notesRepo(bob).createWithContent(NoteId("b1"), "Bob's note", "", "")

        notesRepo(alice).deleteAllForCurrentProfile()

        assertEquals(
            listOf("b1"),
            db.noteDao().listAllForUser(bob.value).map { it.id },
            "the wipe must not reach another profile's rows",
        )
    }

    // ─── Projects ───────────────────────────────────────────────────────────────

    @Test
    fun `the wipe removes the deleted projects of the profile, not only the live ones`() = runTest {
        val repo = projectsRepo(alice)
        repo.create(project("p1"))
        repo.create(project("p2"))
        repo.delete(ProjectId("p2"))

        val removed = repo.deleteAllForCurrentProfile().getOrThrow()

        assertEquals(2, removed, "both the live and the deleted project belong to the profile")
        assertTrue(
            db.projectDao().listAllForUser(alice.value).isEmpty(),
            "the profile must hold no project rows at all — the deleted ones included",
        )
    }

    @Test
    fun `the wipe leaves another profile's projects alone`() = runTest {
        projectsRepo(bob).create(project("b1", bob))

        projectsRepo(alice).deleteAllForCurrentProfile()

        assertEquals(
            listOf("b1"),
            db.projectDao().listAllForUser(bob.value).map { it.id },
            "the wipe must not reach another profile's rows",
        )
    }

    // ─── Saved agenda views ─────────────────────────────────────────────────────

    @Test
    fun `the wipe removes the saved agenda views of the profile`() = runTest {
        val repo = agendaViewsRepo(alice)
        repo.upsert(view("v1"))
        repo.upsert(view("v2"))

        val removed = repo.deleteAllForCurrentProfile().getOrThrow()

        assertEquals(2, removed, "both views belong to the profile")
        assertTrue(
            db.agendaViewDao().listAllForUser(alice.value).isEmpty(),
            "a saved view is profile content: the next run must not see this run's views",
        )
    }

    @Test
    fun `the wipe leaves another profile's saved agenda views alone`() = runTest {
        agendaViewsRepo(bob).upsert(view("b1", bob))

        agendaViewsRepo(alice).deleteAllForCurrentProfile()

        assertEquals(
            listOf("b1"),
            db.agendaViewDao().listAllForUser(bob.value).map { it.id },
            "the wipe must not reach another profile's rows",
        )
    }

    // ─── Fixtures ───────────────────────────────────────────────────────────────

    private fun task(id: String, uid: UserId = alice) = Task(
        id = TaskId(id),
        title = "Task $id",
        userId = uid,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun project(id: String, uid: UserId = alice) = Project(
        id = ProjectId(id),
        name = "Project $id",
        color = 0,
        userId = uid,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun view(id: String, uid: UserId = alice) = SavedAgendaView(
        id = SavedAgendaViewId(id),
        userId = uid,
        name = "View $id",
        sectionsJson = "[]",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    // ─── Repositories under test ────────────────────────────────────────────────

    private fun currentUser(uid: UserId) = FakeProfileAwareCurrentUser(initialUserId = uid)

    private fun taskRepo(uid: UserId, sync: FakeSyncRepository = FakeSyncRepository()) = TaskRepositoryImpl(
        taskDao = db.taskDao(),
        ftsDao = db.ftsDao(),
        clock = Clock.System,
        currentUser = currentUser(uid),
        syncRepository = sync,
        dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser(uid)),
        unitOfWork = FakeUnitOfWork(),
    )

    private fun notesRepo(uid: UserId) = NotesRepositoryImpl(
        noteDao = db.noteDao(),
        ftsDao = db.ftsDao(),
        clock = Clock.System,
        currentUser = currentUser(uid),
        syncRepository = FakeSyncRepository(),
        unitOfWork = FakeUnitOfWork(),
    )

    private fun projectsRepo(uid: UserId) = ProjectsRepositoryImpl(
        projectDao = db.projectDao(),
        ftsDao = db.ftsDao(),
        inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
        clock = Clock.System,
        currentUser = currentUser(uid),
        syncRepository = FakeSyncRepository(),
        unitOfWork = FakeUnitOfWork(),
    )

    private fun agendaViewsRepo(uid: UserId) = SavedAgendaViewsRepositoryImpl(
        agendaViewDao = db.agendaViewDao(),
        currentUser = currentUser(uid),
        clock = Clock.System,
    )
}
