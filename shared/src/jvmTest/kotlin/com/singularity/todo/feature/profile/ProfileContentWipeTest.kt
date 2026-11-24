@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.profile

import co.touchlab.kermit.Logger
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.RoomUnitOfWork
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.DeltaPatch
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.core.sync.FakeSyncScopeProvider
import com.singularity.todo.core.sync.OutboxService
import com.singularity.todo.core.sync.SQL_BIND_CHUNK
import com.singularity.todo.core.sync.SyncEngineState
import com.singularity.todo.core.sync.SyncOutboxDao
import com.singularity.todo.core.sync.SyncOutboxEntity
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncableEntity
import com.singularity.todo.core.sync.productionPatchBuilder
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.data.SavedAgendaViewsRepositoryImpl
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.data.NotesRepositoryImpl
import com.singularity.todo.feature.projects.data.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
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
@Tag("slow:data")
class ProfileContentWipeTest {
    private val alice = UserId("alice")
    private val bob = UserId("bob")
    private val ownerId = "owner-1"
    private val profileId = "profile-1"

    private lateinit var fixture: DatabaseTestFixture
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        fixture = DatabaseTestFixture.inMemory()
        db = fixture.database()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    // ─── Tasks ──────────────────────────────────────────────────────────────────

    @Test
    fun `the wipe removes the archived tasks of the profile, not only the live ones`() =
        runTest {
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
    fun `the wipe leaves another profile's tasks alone`() =
        runTest {
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
    fun `the wipe takes the cross-references of the removed tasks with them`() =
        runTest {
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
    fun `the wipe tells the server the rows are gone`() =
        runTest {
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
    fun `the wipe removes the deleted notes of the profile, not only the live ones`() =
        runTest {
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
    fun `the wipe leaves another profile's notes alone`() =
        runTest {
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
    fun `the wipe removes the deleted projects of the profile, not only the live ones`() =
        runTest {
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
    fun `the wipe leaves another profile's projects alone`() =
        runTest {
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
    fun `the wipe removes the saved agenda views of the profile`() =
        runTest {
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
    fun `the wipe leaves another profile's saved agenda views alone`() =
        runTest {
            agendaViewsRepo(bob).upsert(view("b1", bob))

            agendaViewsRepo(alice).deleteAllForCurrentProfile()

            assertEquals(
                listOf("b1"),
                db.agendaViewDao().listAllForUser(bob.value).map { it.id },
                "the wipe must not reach another profile's rows",
            )
        }

    // ─── The outbox path ────────────────────────────────────────────────────────
    //
    // The tests above run the wipe against a fake sync repository and a fake unit of
    // work. They say what the wipe *deletes*; they cannot say what it *queues*, and
    // they cannot say what happens when the queue write fails half way through —
    // a fake unit of work has no transaction to roll back.
    //
    // The batching changed that contract: the tombstones of a large profile are
    // written in chunks, inside the same transaction as the deletion. So the two
    // questions the batch raises are asked here of the real thing.

    /**
     * The outbox DAO, failing on a chosen insert.
     *
     * Everything else is the real DAO: the point of the failure test is that the
     * *real* statement runs inside the *real* transaction, and that the transaction
     * is what rolls back.
     */
    private class FailingInsertOutboxDao(private val delegate: SyncOutboxDao, private val failOnCall: Int) :
        SyncOutboxDao by delegate {
        var insertAllCalls = 0
            private set

        override suspend fun insertAll(entities: List<SyncOutboxEntity>) {
            insertAllCalls++
            if (insertAllCalls == failOnCall) {
                throw IllegalStateException("the outbox write failed")
            }
            delegate.insertAll(entities)
        }
    }

    @Test
    fun `the wipe queues one tombstone per removed task and leaves another account's work alone`() =
        runTest {
            val repo = taskRepoWithOutbox(alice)
            db.taskDao().upsert(taskEntity("t1", alice, "First"))
            db.taskDao().upsert(taskEntity("t2", alice, "Second"))
            db.taskDao().upsert(taskEntity("b1", bob, "Not mine"))
            db.taskDao().upsertTagCrossRefForUser("t1", "tag-1", alice.value)
            db.taskDao().upsertTagCrossRefForUser("b1", "tag-9", bob.value)
            db.taskDao().upsertDependencyForUser("t1", "t2", "BLOCKS", alice.value)

            val removed = repo.deleteAllForCurrentProfile()

            assertTrue(removed.isSuccess, "the wipe failed: ${removed.exceptionOrNull()}")
            assertEquals(2, removed.getOrThrow(), "both of the profile's tasks are removed")

            assertTrue(
                db.taskDao().listAllForUser(alice.value).isEmpty(),
                "the profile's tasks are gone",
            )
            assertEquals(
                listOf("b1"),
                db.taskDao().listAllForUser(bob.value).map { it.id },
                "the other account's task stays",
            )

            // The relations of the removed rows go with them; another account's
            // relations are not touched.
            assertTrue(
                db.taskDao().listAllTagsForUser(alice.value).isEmpty(),
                "the tag references of the removed tasks are gone",
            )
            assertTrue(
                db.taskDao().listAllDependenciesForUser(alice.value).isEmpty(),
                "the dependency edges of the removed tasks are gone",
            )
            assertEquals(
                listOf("tag-9"),
                db.taskDao().listAllTagsForUser(bob.value).map { it.tagId },
                "the other account's tag reference stays",
            )

            // One queued patch per removed task — nothing is summarised away.
            val queued = queuedPatches()
            assertEquals(
                listOf("t1", "t2"),
                queued.map { it.entityId }.sorted(),
                "one patch per removed task",
            )

            // The tombstone is a faithful copy of the task it stands for, marked as
            // archived: the server has to learn that the task is gone, and it can
            // only learn it from the copy.
            val patch =
                StableJson.decodeFromString(
                    DeltaPatch.serializer(),
                    queued.first { it.entityId == "t1" }.payload,
                )
            assertTrue(
                patch.ops.any { it.field == "archivedAt" },
                "the tombstone says the task is archived: ${patch.ops.map { it.field }}",
            )
            assertTrue(
                patch.ops.any { it.field == "title" },
                "the tombstone carries the task's own state: ${patch.ops.map { it.field }}",
            )
        }

    /**
     * A failure in a later chunk rolls back everything the wipe had done.
     *
     * The wipe deletes the rows and queues the tombstones in one transaction. When the
     * outbox write fails on the second chunk — after the first chunk's patches were
     * already written — the transaction has to undo *both*: the deletion and the rows
     * that were already queued. Anything else would leave the profile half wiped and
     * the server told about rows the device still holds.
     */
    @Test
    fun `a failure in a later chunk rolls back the deletion and the queued patches`() =
        runTest {
            val outbox = FailingInsertOutboxDao(db.syncOutboxDao(), failOnCall = 2)
            val repo = taskRepoWithOutbox(alice, outbox)
            val ids = (0..SQL_BIND_CHUNK).map { "t$it" }
            ids.forEach { id -> db.taskDao().upsert(taskEntity(id, alice)) }

            val removed = repo.deleteAllForCurrentProfile()

            assertTrue(removed.isFailure, "the wipe must report the failure")
            assertEquals(
                2,
                outbox.insertAllCalls,
                "the batch is written in chunks, so there is a second chunk to fail",
            )
            assertEquals(
                ids.size,
                db.taskDao().listAllForUser(alice.value).size,
                "the deletion is rolled back: the tasks are still there",
            )
            assertTrue(
                queuedPatches().isEmpty(),
                "the patches queued before the failure are rolled back too",
            )
            assertTrue(
                db.syncShadowDao().getAll(ownerId, profileId, ids.take(SQL_BIND_CHUNK)).isEmpty(),
                "the shadows written before the failure are rolled back too",
            )
        }

    // ─── Fixtures ───────────────────────────────────────────────────────────────

    private fun task(
        id: String,
        uid: UserId = alice,
    ) = Task(
        id = TaskId(id),
        title = "Task $id",
        userId = uid,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    /**
     * A task row, written straight to the table.
     *
     * The outbox tests seed this way rather than through `create`: a row written by
     * the repository carries a queued patch of its own, and the assertion about the
     * queue is about the *wipe's* patches.
     */
    private fun taskEntity(
        id: String,
        uid: UserId,
        title: String = "Task $id",
    ) = TaskEntity(
        id = id,
        title = title,
        description = null,
        priority = TaskPriority.None,
        kind = TaskKind.Task,
        projectId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        completedAt = null,
        archivedAt = null,
        createdAt = 0L,
        updatedAt = 0L,
        userId = uid.value,
    )

    private fun project(
        id: String,
        uid: UserId = alice,
    ) = Project(
        id = ProjectId(id),
        name = "Project $id",
        color = 0,
        userId = uid,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun view(
        id: String,
        uid: UserId = alice,
    ) = SavedAgendaView(
        id = SavedAgendaViewId(id),
        userId = uid,
        name = "View $id",
        sectionsJson = "[]",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    // ─── Repositories under test ────────────────────────────────────────────────

    private fun currentUser(uid: UserId) = FakeProfileAwareCurrentUser(initialUserId = uid)

    private fun taskRepo(
        uid: UserId,
        sync: FakeSyncRepository = FakeSyncRepository(),
    ) = TaskRepositoryImpl(
        taskDao = db.taskDao(),
        textIndex = db.textIndex(currentUser(uid)),
        clock = Clock.System,
        currentUser = currentUser(uid),
        syncRepository = sync,
        dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser(uid)),
        unitOfWork = FakeUnitOfWork(),
    )

    /**
     * The repository as the wipe meets it in production: the real unit of work over
     * the real database, and the real outbox service behind the sync seam.
     *
     * The only thing that is not production here is the [SyncRepository] seam: the
     * engine would need a transport, a runner and a coordinator to answer it, and
     * none of those take part in the wipe. What the wipe needs from that seam is the
     * batched enqueue, and that is the real [OutboxService] over the real DAOs.
     */
    private fun taskRepoWithOutbox(
        uid: UserId,
        outboxDao: SyncOutboxDao = db.syncOutboxDao(),
    ): TaskRepositoryImpl {
        val user = currentUser(uid)
        val outboxService =
            OutboxService(
                outboxDao = outboxDao,
                patchBuilder = productionPatchBuilder(db.syncShadowDao()),
                scopeProvider = FakeSyncScopeProvider(SyncScope(ownerId = ownerId, profileId = profileId)),
                engineState =
                    SyncEngineState(
                        log = Logger.withTag("ProfileContentWipeTest"),
                        crashReporter = NoOpCrashReportingPort(),
                    ),
                monoNow = { Clock.System.now().toEpochMilliseconds() },
            )
        val sync =
            object : FakeSyncRepository() {
                override suspend fun enqueue(entity: SyncableEntity): Result<Unit> = outboxService.enqueue(entity)

                override suspend fun enqueue(entities: List<SyncableEntity>): Result<Unit> =
                    outboxService.enqueueAll(entities)
            }
        return TaskRepositoryImpl(
            taskDao = db.taskDao(),
            textIndex = db.textIndex(user),
            clock = Clock.System,
            currentUser = user,
            syncRepository = sync,
            dependencyValidator = DependencyValidatorImpl(db.taskDao(), user),
            unitOfWork = RoomUnitOfWork(db),
        )
    }

    /** The patches the wipe queued under the active scope. */
    private suspend fun queuedPatches() =
        db.syncOutboxDao().getPending(
            now = Long.MAX_VALUE,
            ownerId = ownerId,
            profileId = profileId,
        )

    private fun notesRepo(uid: UserId) =
        NotesRepositoryImpl(
            noteDao = db.noteDao(),
            textIndex = db.textIndex(currentUser(uid)),
            clock = Clock.System,
            currentUser = currentUser(uid),
            syncRepository = FakeSyncRepository(),
            unitOfWork = FakeUnitOfWork(),
        )

    private fun projectsRepo(uid: UserId) =
        ProjectsRepositoryImpl(
            projectDao = db.projectDao(),
            inheritedTagGroupDao = db.projectInheritedTagGroupDao(),
            clock = Clock.System,
            currentUser = currentUser(uid),
            syncRepository = FakeSyncRepository(),
            unitOfWork = FakeUnitOfWork(),
        )

    private fun agendaViewsRepo(uid: UserId) =
        SavedAgendaViewsRepositoryImpl(
            agendaViewDao = db.agendaViewDao(),
            currentUser = currentUser(uid),
            clock = Clock.System,
        )
}
