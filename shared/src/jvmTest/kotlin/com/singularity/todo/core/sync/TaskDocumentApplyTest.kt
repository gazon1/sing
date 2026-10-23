package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.UserId
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag

/**
 * The path that applies a task document, applied against the row that is
 * already here.
 *
 * ## Why the path and not the function
 *
 * [mergeWithLocalRow] is a pure function and its own test says what it does.
 * What matters is that the path that applies a document goes through it: the
 * pull handler and the revert of a lost race both write documents through
 * [SyncDocumentWriter], and both write them whole. A merge that nobody calls
 * is a merge that does not happen.
 *
 * ## What the documents look like
 *
 * The documents here are the documents a device sends: a whole task, with the
 * keys an older device did not know about left out. A document that carried
 * only an id and a title would be a document no device sends — the decode of
 * it fails on the fields the model cannot do without — and a test built on it
 * would fail for the wrong reason.
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

    /** The document a device sends: the whole task, minus the keys it does not know. */
    private fun documentOf(task: Task, without: Set<String> = emptySet()): JsonObject =
        JsonObject(task.toJson().filterKeys { it !in without })

    @Test
    fun `a document that carries no series id does not take the task out of its series`() = runTest {
        val repo = FakeTaskRepository()
        repo.add(local)

        writer(repo).upsert(
            DocType.Task,
            documentOf(local.copy(title = "Pay the rent in full"), without = setOf("seriesId")),
        )

        val stored = assertNotNull(repo.get(TaskId.fromString("t1")))
        assertEquals(series, stored.seriesId, "the task is still in its series")
        assertEquals("Pay the rent in full", stored.title, "the document still wins where it speaks")
    }

    @Test
    fun `a document that says the task stands alone takes it out of the series`() = runTest {
        val repo = FakeTaskRepository()
        repo.add(local)

        writer(repo).upsert(
            DocType.Task,
            JsonObject(documentOf(local) + ("seriesId" to JsonNull)),
        )

        val stored = assertNotNull(repo.get(TaskId.fromString("t1")))
        assertNull(stored.seriesId, "the document said the task stands alone")
    }

    @Test
    fun `a document that carries no estimate does not take the estimate off the task`() = runTest {
        val repo = FakeTaskRepository()
        repo.add(local)

        writer(repo).upsert(
            DocType.Task,
            documentOf(local, without = setOf("estimateMinutes")),
        )

        val stored = assertNotNull(repo.get(TaskId.fromString("t1")))
        assertEquals(30, stored.estimateMinutes, "the estimate of the task is still the estimate of the task")
    }

    @Test
    fun `a document about a task this device has never seen is the task`() = runTest {
        val repo = FakeTaskRepository()
        val arriving = local.copy(id = TaskId.fromString("t9"), seriesId = null)

        writer(repo).upsert(DocType.Task, documentOf(arriving))

        val stored = assertNotNull(repo.get(TaskId.fromString("t9")))
        assertEquals("Pay the rent", stored.title)
        assertNull(stored.seriesId, "the document said nothing about a series, and there is no row to say more")
    }

    @Test
    fun `a document of another profile does not read this profile's row`() = runTest {
        val repo = FakeTaskRepository()
        repo.add(local)
        val foreign = local.copy(
            id = TaskId.fromString("t9"),
            userId = UserId("bob"),
            seriesId = null,
        )

        writer(repo).upsert(DocType.Task, documentOf(foreign))

        // The row of another profile is written as the document says — and the
        // merge did not read this profile's row to fill it: the document said
        // no series, so the row of another profile has no series.
        assertNull(
            repo.tasks.value[TaskId.fromString("t9").value]?.seriesId,
            "the row of another profile is written as the document says",
        )
        assertEquals(
            series,
            assertNotNull(repo.get(TaskId.fromString("t1"))).seriesId,
            "the row of this profile is untouched",
        )
    }

    @Test
    fun `the id of the document names the row the merge reads`() = runTest {
        // The row the merge reads is the row of the task the document is about,
        // not the row of some other task on this device.
        val repo = FakeTaskRepository()
        repo.add(local.copy(id = TaskId.fromString("t2"), seriesId = null))
        repo.add(local)

        writer(repo).upsert(
            DocType.Task,
            documentOf(local.copy(title = "Pay the rent in full"), without = setOf("seriesId")),
        )

        val other = assertNotNull(repo.get(TaskId.fromString("t2")))
        assertNull(other.seriesId, "the other task is not the task the document is about")
    }
}
