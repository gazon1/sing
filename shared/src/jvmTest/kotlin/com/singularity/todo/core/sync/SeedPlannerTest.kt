@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagGroupRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one-time upload of data that existed before the user signed in.
 *
 * ## What is actually being asserted
 *
 * Not "the planner called enqueue N times" — that describes the implementation.
 * The two properties that matter are behavioural and pull in opposite directions:
 * a second sign-in on the same account must upload nothing, and an interrupted
 * seed must resume rather than lose the rest. Both are checked, and the second one
 * is checked *through* the outbox rather than against a call count, because
 * "resumes without duplicates" is a claim about what the queue ends up holding.
 */
@Tag("fast")
class SeedPlannerTest {

    private val state = FakeSyncStateRepository()
    private val scope = SyncScope(ownerId = "owner-1", profileId = "work")

    /** One entry per entity, the way the real outbox coalesces. */
    private val outbox = LinkedHashMap<String, SyncableEntity>()

    private val enqueued = mutableListOf<String>()

    @Test
    fun `existing documents are queued through the ordinary push path`() = runTest {
        val tasks = FakeTaskRepository().apply { seed(task("t1"), task("t2")) }

        val count = planner(tasks).plan(scope).getOrThrow()

        assertEquals(2, count)
        assertEquals(listOf("t1", "t2"), enqueued)
        assertEquals(setOf("t1", "t2"), outbox.keys, "each entity occupies one outbox row")
    }

    @Test
    fun `a second sign-in on the same account uploads nothing`() = runTest {
        val tasks = FakeTaskRepository().apply { seed(task("t1")) }
        val planner = planner(tasks)

        assertEquals(1, planner.plan(scope).getOrThrow())
        assertEquals(0, planner.plan(scope).getOrThrow(), "the second sign-in must upload nothing")
        assertEquals(listOf("t1"), enqueued)
    }

    @Test
    fun `the marker is per scope, so a second profile still seeds`() = runTest {
        // The requirement is about a second sign-in on the *same* account. A flag
        // keyed by device would answer that question and this one wrong, and the
        // user's other profile would silently never reach the server.
        val tasks = FakeTaskRepository().apply { seed(task("t1")) }
        val planner = planner(tasks)

        planner.plan(scope).getOrThrow()
        planner.plan(SyncScope(ownerId = "owner-1", profileId = "home")).getOrThrow()

        assertEquals(listOf("t1", "t1"), enqueued)
    }

    @Test
    fun `an interrupted seed resumes and leaves no duplicate`() = runTest {
        val tasks = FakeTaskRepository().apply { seed(task("t1"), task("t2"), task("t3")) }
        val planner = planner(tasks, failing = setOf("t2"))

        // The first attempt loses one document but still marks the scope. That
        // ordering is deliberate: marking last costs a re-run, marking first
        // strands whatever was not yet queued with no way back.
        val firstRun = planner.plan(scope).getOrThrow()
        assertEquals(2, firstRun)

        val secondRun = planner(tasks).plan(scope).getOrThrow()

        assertEquals(3, secondRun, "the missed document is retried")
        assertEquals(
            setOf("t1", "t2", "t3"),
            outbox.keys,
            "resuming replaces an entity's row rather than adding a second one",
        )
    }

    @Test
    fun `an entity that cannot be queued does not stop the others`() = runTest {
        val tasks = FakeTaskRepository().apply { seed(task("t1"), task("t2"), task("t3")) }

        val count = planner(tasks, failing = setOf("t2")).plan(scope).getOrThrow()

        assertEquals(2, count)
        assertEquals(listOf("t1", "t3"), enqueued)
    }

    @Test
    fun `a device with nothing to seed still marks the scope`() = runTest {
        // Otherwise every sync re-scans every table for a user who has no data,
        // which is a cost paid on the common path to serve a rare one.
        assertEquals(0, planner(FakeTaskRepository()).plan(scope).getOrThrow())
        assertTrue(state.isSeedCompleted(scope))
    }

    // ── Infrastructure ──────────────────────────────────────────────────────

    private fun planner(tasks: FakeTaskRepository, failing: Set<String> = emptySet()) = SeedPlanner(
        stateRepository = state,
        enqueue = { entity ->
            if (entity.syncId in failing) {
                Result.failure(AppError.Persistence("queue is full"))
            } else {
                enqueued += entity.syncId
                // The outbox coalesces per entity: delete-then-insert, never two rows.
                outbox.remove(entity.syncId)
                outbox[entity.syncId] = entity
                Result.success(Unit)
            }
        },
        taskRepo = tasks,
        noteRepo = FakeNotesRepository(),
        projectRepo = FakeProjectsRepository(),
        tagRepo = FakeTagsRepository(),
        tagGroupRepo = FakeTagGroupRepository(),
    )

    private fun task(id: String) = Task(
        id = TaskId.fromString(id),
        title = "task $id",
        userId = TestUsers.DEFAULT,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
