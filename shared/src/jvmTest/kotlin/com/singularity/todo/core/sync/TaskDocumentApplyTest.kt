package com.singularity.todo.core.sync

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagGroupRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The path that applies a task document, applied against the row that is
 * already here.
 *
 * ## Why the path and not the function
 *
 * [mergeWithLocalRow] is a pure function and its own test says what it does.
 * What matters is that the path that applies a document goes through it: the
 * pull handler and the revert of a lost race both write documents, and both
 * write them whole. A merge that nobody calls is a merge that does not happen.
 *
 * ## What the device sees
 *
 * A device that predates the series sends no `seriesId` key. The task must
 * stay in its series on the device that pulled that document — and it must
 * leave the series when the document says so.
 */
@Tag("fast")
class TaskDocumentApplyTest {

    private val series = TaskSeriesId.fromString("s1")

    private val local = Task(
        id = TaskId.fromString("t1"),
        title = "Pay the rent",
        userId = TestUsers.DEFAULT,
        seriesId = series,
        estimateMinutes = 30,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun writer(repo: FakeTaskRepository) = SyncDocumentWriter(
        taskRepo = repo,
        noteRepo = FakeNotesRepository(),
        projectRepo = FakeProjectsRepository(),
        tagRepo = FakeTagsRepository(),
        tagGroupRepo = FakeTagGroupRepository(),
        timeTrackingRepo = FakeTimeTrackingRepository(FakeClock()),
    )

    private fun document(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        JsonObject(entries.toMap())

    @Test
    fun `a document without a series id does not take the task out of its series`() = runTest {
        val repo = FakeTaskRepository()
        repo.upsert(local)

        writer(repo).upsert(
            DocType.Task,
            document(
                "id" to JsonPrimitive("t1"),
                "title" to JsonPrimitive("Pay the rent in full"),
            ),
        )

        val stored = assertNotNull(repo.get(TaskId.fromString("t1")))
        assertEquals(series, stored.seriesId, "the task is still in its series")
        assertEquals("Pay the rent in full", stored.title, "the document still wins where it speaks")
    }

    @Test
    fun `a document that says the task stands alone takes it out of the series`() = runTest {
        val repo = FakeTaskRepository()
        repo.upsert(local)

        writer(repo).upsert(
            DocType.Task,
            document(
                "id" to JsonPrimitive("t1"),
                "title" to JsonPrimitive("Pay the rent"),
                "seriesId" to JsonPrimitive(null as String?),
            ),
        )

        val stored = assertNotNull(repo.get(TaskId.fromString("t1")))
        assertEquals(null, stored.seriesId, "the document said the task stands alone")
    }

    @Test
    fun `a document without an estimate does not take the estimate off the task`() = runTest {
        val repo = FakeTaskRepository()
        repo.upsert(local)

        writer(repo).upsert(
            DocType.Task,
            document(
                "id" to JsonPrimitive("t1"),
                "title" to JsonPrimitive("Pay the rent"),
            ),
        )

        val stored = assertNotNull(repo.get(TaskId.fromString("t1")))
        assertEquals(30, stored.estimateMinutes, "the estimate of the task is still the estimate of the task")
    }
}
