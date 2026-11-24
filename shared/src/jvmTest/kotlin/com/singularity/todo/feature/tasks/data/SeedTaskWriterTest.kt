package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.RoomUnitOfWork
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.CrossUserWriteException
import com.singularity.todo.core.seed.SeedTaskWriter
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The seed's batch write, against a real database: every row lands, and no patch is
 * enqueued for any of them.
 *
 * ## What this pins
 *
 * The whole point of `SeedTaskWriter` (ADR
 * `2026-11-24-the-seed-writes-its-rows-without-a-patch`) is a trade the ordinary create
 * cannot make: a fixture row is asserted on where it was written and never reaches a
 * server, so the outbox write per row buys nothing and costs the ~30 ms that made a
 * thousand-row seed outlast every Maestro wait.
 *
 * Both halves matter, and each fails in the opposite direction if the other is only
 * assumed:
 *
 * - A write that lost the rows would make the seed a silent no-op — the flow would wait
 *   for a row nothing wrote.
 * - A write that enqueued would restore the cost, and nothing else would notice: the
 *   outbox would simply carry a thousand patches nobody asked for.
 *
 * The tags and the follow-up edges ride with the row too — checked against the
 * cross-ref tables rather than against the row, because that is where they live and it
 * is the assertion a writer that only upserted the row would fail. One transaction holds
 * the batch, which is why this runs against SQLite rather than a fake, exactly as
 * `UnitOfWorkIsAtomicTest` does for the same reason.
 */
@Tag("slow")
@Tag("slow:data")
class SeedTaskWriterTest {
    private val user = UserId("u1")

    private lateinit var fixture: DatabaseTestFixture
    private lateinit var db: AppDatabase
    private lateinit var sync: FakeSyncRepository
    private lateinit var repo: TaskRepositoryImpl
    private lateinit var seeds: SeedTaskWriter

    @BeforeTest
    fun setUp() =
        runTest {
            fixture = DatabaseTestFixture.inMemory()
            db = fixture.database()
            sync = FakeSyncRepository(this)
            val currentUser =
                FakeProfileAwareCurrentUser(
                    FakeAuthRepository(Session.Anonymous(user)),
                    scope = backgroundScope,
                )
            repo =
                TaskRepositoryImpl(
                    taskDao = db.taskDao(),
                    textIndex = db.textIndex(currentUser),
                    clock = Clock.System,
                    currentUser = currentUser,
                    syncRepository = sync,
                    dependencyValidator = DependencyValidatorImpl(db.taskDao(), currentUser),
                    unitOfWork = RoomUnitOfWork(db),
                )
            seeds =
                SeedTaskDaoWriter(taskDao = db.taskDao(), currentUser = currentUser, unitOfWork = RoomUnitOfWork(db))
        }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    private fun task(
        id: String,
        title: String = "Seeded $id",
    ) = Task(
        id = TaskId(id),
        title = title,
        userId = user,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `every row lands`() =
        runTest {
            val seeded = (1..50).map { task("t$it") }

            val written = seeds.writeFixtures(seeded)

            // The ids, not just the count: a writer that returned 50 rows of the wrong
            // fixture would satisfy a size check while the flow asserted on nothing.
            assertEquals(seeded.map { it.id }, written.map { it.id }, "the rows come back as they went in")
            assertEquals(
                seeded.map { it.id }.toSet(),
                db.taskDao().listAllForUser(user.value).map { TaskId(it.id) }.toSet(),
                "every seeded row must be in the table, under its own id",
            )
            assertEquals(
                seeded.map { it.title }.toSet(),
                db.taskDao().listAllForUser(user.value).map { it.title }.toSet(),
                "and it must be the row that was asked for, not a neighbouring one",
            )
        }

    @Test
    fun `the tags and follow-up edges of a row ride with it`() =
        runTest {
            // What makes the batch writer a *write* and not an insert of bare rows. A
            // fixture that asked for a tagged task or a follow-up edge and got neither
            // would be a flow asserting on data the seed never wrote — and both writes
            // are the ones a future "simplification" of this path would drop first.
            val tag = TagId("tag-1")
            val dependency = TaskId("dep-1")

            seeds.writeFixtures(
                listOf(
                    task("t1").copy(tags = listOf(tag)),
                    task("t2").copy(followsUp = setOf(dependency)),
                ),
            )

            assertEquals(
                listOf(TaskTagCrossRef(taskId = "t1", tagId = "tag-1")),
                db.taskDao().listAllTagsForUser(user.value),
                "the seeded row must carry exactly the tag it asked for, on the right row",
            )
            assertEquals(
                listOf(
                    TaskDependencyCrossRef(
                        taskId = "t2",
                        dependsOnTaskId = "dep-1",
                        verb = DependencyVerb.FOLLOWS_UP.name,
                    ),
                ),
                db.taskDao().listAllDependenciesForUser(user.value),
                "the edge must point at the named task, with the verb the writer meant",
            )
        }

    @Test
    fun `a tag the row already had is added to, not replaced`() =
        runTest {
            // Pins the contract the port documents: this is an insert, the way `create`
            // is, and `setTags` is the operation that reconciles a whole set. A future
            // change that turned this into replacement semantics would silently drop a
            // relationship the caller never mentioned — which is exactly what `create`
            // does not do, so the two paths would disagree about the same table.
            seeds.writeFixtures(listOf(task("t1").copy(tags = listOf(TagId("tag-a")))))
            seeds.writeFixtures(listOf(task("t1").copy(tags = listOf(TagId("tag-b")))))

            assertEquals(
                setOf("tag-a", "tag-b"),
                db
                    .taskDao()
                    .listAllTagsForUser(user.value)
                    .map { it.tagId }
                    .toSet(),
                "the second write adds its tag; it does not remove the one already there",
            )
        }

    @Test
    fun `the ordinary create still enqueues its patch`() =
        runTest {
            // The control for the test below. Without it, "no patch" could be true of a
            // writer that never enqueues *anything*, and this suite would pass while the
            // ordinary create quietly stopped syncing.
            repo.create(task("t1")).getOrThrow()

            assertEquals(
                1,
                sync.enqueuedEntities.size,
                "a user's create must still enqueue the patch that sends it to the server",
            )
        }

    @Test
    fun `no row enqueues a patch`() =
        runTest {
            seeds.writeFixtures((1..20).map { task("t$it") })

            assertTrue(
                sync.enqueuedEntities.isEmpty(),
                "a seeded fixture is asserted on where it was written and never synced, so the " +
                    "outbox must stay empty — ${sync.enqueuedEntities.size} patches were enqueued",
            )
            assertEquals(
                0,
                db.syncOutboxDao().countForOwner(user.value),
                "the outbox table must not carry a row of the fixture",
            )
        }

    @Test
    fun `a failure halfway leaves nothing behind`() =
        runTest {
            val seeded = (1..5).map { task("t$it") }
            // The first row carries both cross-refs, so the rollback of the relationship
            // writes is part of what this test proves: a tag or an edge that escaped the
            // transaction would survive with no row to point from.
            val withRelations =
                listOf(
                    seeded[0].copy(tags = listOf(TagId("tag-1")), followsUp = setOf(TaskId("dep-1"))),
                    task("t2").copy(userId = UserId("intruder")),
                ) + seeded.drop(2)

            assertFailsWith<CrossUserWriteException> {
                // The second row names a profile this device is not on: the guard refuses
                // it, and the batch has already written the first row by then. A narrow
                // type on purpose — catching `Throwable` would let an unrelated failure
                // before the second row pass this test without proving the rollback.
                seeds.writeFixtures(withRelations)
            }

            assertTrue(
                db.taskDao().listAllForUser(user.value).isEmpty(),
                "a batch that failed must roll every row of it back, not keep the ones it wrote",
            )
            assertTrue(
                db.taskDao().listAllTagsForUser(user.value).isEmpty(),
                "a rolled-back row must not leave its tag behind",
            )
            assertTrue(
                db.taskDao().listAllDependenciesForUser(user.value).isEmpty(),
                "a rolled-back row must not leave its follow-up edge behind",
            )
        }
}
