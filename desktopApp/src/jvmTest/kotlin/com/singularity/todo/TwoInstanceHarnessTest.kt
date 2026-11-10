package com.singularity.todo

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.sync.BatchPushRequest
import com.singularity.todo.core.sync.DeltaPatch
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.FieldChange
import com.singularity.todo.core.sync.FieldOp
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.helpers.TwoInstanceHarness
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Two app instances, two databases, one server.
 *
 * These are the two facts every desktop sync carrier rests on, and neither of
 * them could be shown by a single instance:
 *
 * - the instances do **not** share a database, so a row written on one is not
 *   on the other;
 * - they do share a **server**, so a change pushed by one is seen by the other.
 *
 * The carriers that follow build on the same harness; this class is the proof
 * that the harness is wired the way they need it.
 *
 * @see com.singularity.todo.test.helpers.TwoInstanceHarness
 */
@Tag("slow")
@Tag("slow:data")
class TwoInstanceHarnessTest {
    /**
     * A row written on instance A is invisible to instance B.
     *
     * The two database files are asserted first: if the instances shared one
     * file, the absence below would prove nothing — the row would simply not
     * have been written yet.
     */
    @Test
    fun `a row written on one instance is absent from the other instance's database`() =
        runTest {
            TwoInstanceHarness.start().use { harness ->
                assertNotEquals(
                    harness.instanceA.databasePath,
                    harness.instanceB.databasePath,
                    "the two instances must not open the same database file",
                )
                // The paths are strings the harness built. These two say the graphs
                // actually use those files rather than the in-memory fake.
                assertSame(
                    harness.instanceA.database,
                    harness.instanceA.koin.get<AppDatabase>(),
                    "instance A's graph must resolve A's Room database",
                )
                assertSame(
                    harness.instanceB.database,
                    harness.instanceB.koin.get<AppDatabase>(),
                    "instance B's graph must resolve B's Room database",
                )

                val taskId = TaskId("two-instance-task")
                val userA =
                    harness.instanceA.koin
                        .get<ProfileAwareCurrentUser>()
                        .scopedUserId.value
                val userB =
                    harness.instanceB.koin
                        .get<ProfileAwareCurrentUser>()
                        .scopedUserId.value
                // Repository reads are owner-scoped. Unless both instances scope to
                // the same user, B's null below also happens when the two graphs share
                // one file, so the assertion would prove nothing.
                assertEquals(userA, userB, "both instances must scope to the same user")
                val userId = userA
                val written =
                    harness.instanceA.koin.get<TaskRepository>().create(
                        Task(
                            id = taskId,
                            title = "Written on A",
                            description = null,
                            userId = userId,
                            createdAt = Clock.System.now(),
                            updatedAt = Clock.System.now(),
                        ),
                    )
                assertTrue(written.isSuccess, "the write on instance A failed: ${written.exceptionOrNull()}")

                assertNotNull(
                    harness.instanceA.koin
                        .get<TaskRepository>()
                        .get(taskId),
                    "instance A must see the row it wrote",
                )
                assertNull(
                    harness.instanceB.koin
                        .get<TaskRepository>()
                        .get(taskId),
                    "instance B must not see a row written on instance A",
                )
            }
        }

    /**
     * A change pushed by instance A is pulled by instance B.
     *
     * Both instances act as the same account — a sync is a conversation between
     * two devices of one account — and the event crosses the server rather than
     * a direct call from A to B.
     */
    @Test
    fun `a change pushed on one instance is retrieved by the other instance's client`() =
        runTest {
            TwoInstanceHarness.start().use { harness ->
                assertEquals(
                    harness.instanceA.account,
                    harness.instanceB.account,
                    "both instances must act as the same account",
                )
                assertNotSame(
                    harness.instanceA.syncClient,
                    harness.instanceB.syncClient,
                    "the instances must not share a client object",
                )
                // The push and pull below go through the graphs, not the handles the
                // harness handed out. If the SyncApiClient override ever stopped
                // winning, these two assertions fail instead of the test staying green
                // while both graphs talk to the production transport.
                assertSame(
                    harness.instanceA.syncClient,
                    harness.instanceA.koin.get<SyncApiClient>(),
                    "instance A's graph must resolve the shared server's client",
                )
                assertSame(
                    harness.instanceB.syncClient,
                    harness.instanceB.koin.get<SyncApiClient>(),
                    "instance B's graph must resolve the shared server's client",
                )

                // A patch is a set of field operations under a clock: the server
                // owns the row's identity and counts, and it merges what a
                // patch names field by field. A patch without a clock and
                // without an operation is refused — there is nothing to order
                // and nothing to write.
                val patch =
                    DeltaPatch(
                        patchId = "patch-1",
                        entityId = "task-1",
                        entityType = DocType.Task,
                        baseVersion = 0L,
                        hlc = Hlc.of(1_000L, 0, "device-a"),
                        ops = listOf(FieldChange("title", FieldOp.SET, JsonPrimitive("Written on A"))),
                    )
                val response =
                    harness.instanceA.koin.get<SyncApiClient>().batchPush(
                        BatchPushRequest(
                            deviceId = "device-a",
                            profileId = "profile-1",
                            patches = listOf(patch),
                        ),
                    )
                assertTrue(response.results.single().ok, "the push must be accepted by the server")

                val events =
                    harness.instanceB.koin
                        .get<SyncApiClient>()
                        .getEventsSince(0L)
                assertEquals(1, events.size, "instance B must pull exactly the event instance A pushed")
                assertEquals("task-1", events.single().entityId)
            }
        }
}
