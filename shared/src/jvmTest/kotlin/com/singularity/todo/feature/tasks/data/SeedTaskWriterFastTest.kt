package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.CrossUserWriteException
import com.singularity.todo.core.seed.SeedTaskWriter
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The seed's batch writer, against the in-memory database — the **fast** half of its
 * coverage.
 *
 * ## Why this class exists next to `SeedTaskWriterTest`
 *
 * `SeedTaskWriterTest` runs against real SQLite, which is the only place a transaction
 * is a transaction, so it is tagged `slow` — and the population a pull request runs is
 * `fast` (`config/test-populations.properties`). A production write path whose only
 * coverage lives in the slow half is a path a pull request can break without any check
 * noticing: the tests exist, and they run on the nightly, which is not when the
 * regression is introduced.
 *
 * So the orchestration is pinned twice, at two costs. This class answers "does the writer
 * do the right things to the rows" against `FakeAppDatabase`, which implements the real
 * `TaskDao` interface with no Room and no SQLite. `SeedTaskWriterTest` answers the
 * questions that need a real database — one commit or none — and is the one that would
 * notice `UnitOfWork` no longer rolling back.
 *
 * What this class deliberately does **not** prove: atomicity. `FakeUnitOfWork` runs the
 * block and does not roll it back, so a test here can only show the failure propagates.
 * That guarantee is the slow class's job, and saying so here is the point of splitting
 * them.
 */
@Tag("fast")
class SeedTaskWriterFastTest {
    private val user = UserId("u1")

    private val db = FakeAppDatabase()
    private val currentUser =
        FakeProfileAwareCurrentUser(FakeAuthRepository(Session.Anonymous(user)))

    private val seeds: SeedTaskWriter =
        SeedTaskDaoWriter(taskDao = db.taskDao(), currentUser = currentUser, unitOfWork = FakeUnitOfWork())

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
    fun `the rows land under the active profile`() =
        runTest {
            val seeded = (1..10).map { task("t$it") }

            val written = seeds.writeFixtures(seeded)

            assertEquals(seeded.map { it.id }, written.map { it.id }, "the rows come back as they went in")
            assertEquals(
                seeded.map { it.id }.toSet(),
                db
                    .taskDao()
                    .listAllForUser(user.value)
                    .map { TaskId(it.id) }
                    .toSet(),
                "every row must be in the table under the active profile",
            )
        }

    @Test
    fun `the tags and the follow-up edges ride with the row`() =
        runTest {
            seeds.writeFixtures(
                listOf(
                    task("t1").copy(tags = listOf(TagId("tag-1"))),
                    task("t2").copy(followsUp = setOf(TaskId("dep-1"))),
                ),
            )

            assertEquals(
                listOf("tag-1"),
                db.taskDao().listAllTagsForUser(user.value).map { it.tagId },
                "the tag of the seeded row must be in the cross-ref table",
            )
            val edge = db.taskDao().listAllDependenciesForUser(user.value).single()
            assertEquals("t2", edge.taskId, "the edge belongs to the row that asked for it")
            assertEquals("dep-1", edge.dependsOnTaskId, "and points at the task it named")
            assertEquals(
                DependencyVerb.FOLLOWS_UP.name,
                edge.verb,
                "with the verb the writer meant — not the default BLOCKS",
            )
        }

    @Test
    fun `a row of another profile is refused`() =
        runTest {
            assertFailsWith<CrossUserWriteException> {
                seeds.writeFixtures(listOf(task("t1"), task("t2").copy(userId = UserId("intruder"))))
            }

            // The rollback itself is the slow class's to prove — `FakeUnitOfWork` does
            // not undo. What this asserts is that the guard ran *at* the foreign row and
            // not before the batch: the row that came first is in the table. `size <= 1`
            // would let a writer that rejected the whole batch up front pass, which is a
            // different behaviour than the one the guard is for.
            assertEquals(
                setOf("t1"),
                db
                    .taskDao()
                    .listAllForUser(user.value)
                    .map { it.id }
                    .toSet(),
                "the row before the refused one was written before the guard stopped the batch",
            )
            assertTrue(
                db.taskDao().listAllForUser("intruder").isEmpty(),
                "nothing may land under the profile that was refused",
            )
        }
}
