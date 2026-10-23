package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.BLOCKED_BY_WIRE_NAME
import com.singularity.todo.feature.tasks.domain.model.TAGS_WIRE_NAME
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire carries the fields the sending device knew about, and a field that
 * is absent is not a claim that the part is empty.
 *
 * ## Why this is a separate question
 *
 * A task document is decoded as a `Task`, and the fields the model cannot do
 * without have defaults: `tags` defaults to an empty list, `blockedBy` to an
 * empty set. A document without the `tags` key therefore decodes into a task
 * that says "no tags" — and the pull applies what the document says, so the
 * tags of every task an old device writes would be taken off.
 *
 * The absence has to be read *before* the decode, where the key is still
 * visible. This file is that reading.
 */
@Tag("fast")
class TaskDocumentPartsTest {

    private class RecordingTaskRepository : FakeTaskRepository() {
        val documents = mutableListOf<TaskDocument>()

        override suspend fun upsert(document: TaskDocument): Task {
            documents += document
            return super.upsert(document)
        }
    }

    private fun task(
        id: String = "t1",
        tags: List<TagId> = emptyList(),
        blockedBy: Set<TaskId> = emptySet(),
    ) = Task(
        id = TaskId(id),
        title = "Pay the rent",
        description = null,
        userId = UserId("alice"),
        tags = tags,
        blockedBy = blockedBy,
        createdAt = Instant.fromEpochMilliseconds(1_000),
        updatedAt = Instant.fromEpochMilliseconds(1_000),
    )

    /**
     * The document as the wire carries it, with the named keys taken away.
     *
     * The keys are the ones the sync allow-list sends: the tags and the
     * dependencies, under the wire names the model gives them.
     */
    private fun wireDocument(task: Task, without: Set<String> = emptySet()): JsonObject {
        val encoded = task.toJson()
        return JsonObject(encoded.filterKeys { it !in without })
    }

    @Test
    fun `a document with the tags key says what it carries`() = runTest {
        val repo = RecordingTaskRepository()
        val writer = fakeSyncDocumentWriter(tasks = repo)
        val tag = TagId("tag-1")

        writer.upsert(DocType.Task, wireDocument(task(tags = listOf(tag))))

        assertEquals(listOf(tag), repo.documents.single().tags, "the document says what it carries")
    }

    @Test
    fun `a document without the tags key says nothing about the tags`() = runTest {
        val repo = RecordingTaskRepository()
        val writer = fakeSyncDocumentWriter(tasks = repo)

        writer.upsert(DocType.Task, wireDocument(task(tags = listOf(TagId("tag-1"))), without = setOf(TAGS_WIRE_NAME)))

        assertNull(repo.documents.single().tags, "the document says nothing about the tags")
    }

    @Test
    fun `a document with the dependsOn key says what it carries`() = runTest {
        val repo = RecordingTaskRepository()
        val writer = fakeSyncDocumentWriter(tasks = repo)
        val dependency = TaskId("t2")

        writer.upsert(DocType.Task, wireDocument(task(blockedBy = setOf(dependency))))

        assertEquals(
            setOf(dependency),
            repo.documents.single().blockedBy,
            "the document says what it carries",
        )
    }

    @Test
    fun `a document without the dependsOn key says nothing about the dependencies`() = runTest {
        val repo = RecordingTaskRepository()
        val writer = fakeSyncDocumentWriter(tasks = repo)

        writer.upsert(
            DocType.Task,
            wireDocument(task(blockedBy = setOf(TaskId("t2"))), without = setOf(BLOCKED_BY_WIRE_NAME)),
        )

        assertNull(repo.documents.single().blockedBy, "the document says nothing about the dependencies")
    }

    @Test
    fun `a document that carries the parts says nothing about the others`() = runTest {
        val repo = RecordingTaskRepository()
        val writer = fakeSyncDocumentWriter(tasks = repo)

        writer.upsert(
            DocType.Task,
            wireDocument(task(tags = listOf(TagId("tag-1"))), without = setOf(BLOCKED_BY_WIRE_NAME)),
        )

        val document = repo.documents.single()
        assertEquals(listOf(TagId("tag-1")), document.tags, "the tags are said")
        assertNull(document.blockedBy, "the dependencies are not")
    }

    /**
     * The double has to keep the parts the document does not carry.
     *
     * A fake that stores the whole task turns an absent claim into a deletion —
     * the very defect the document type was introduced to remove. A test written
     * against such a fake would fail against the fake and pass against
     * production, which is the worst way for a double to lie.
     */
    @Test
    fun `the double keeps the parts the document does not carry`() = runTest {
        val repo = FakeTaskRepository()
        repo.upsert(TaskDocument.of(task(tags = listOf(TagId("tag-1")))))
        val before = repo.tasks.value.getValue("t1")

        repo.upsert(
            TaskDocument(
                task = before,
                tags = null,
                blockedBy = null,
            ),
        )

        assertEquals(
            listOf(TagId("tag-1")),
            repo.tasks.value.getValue("t1").tags,
            "the tags the document said nothing about are still there",
        )
    }

    /**
     * The double has to take the claim when there is one.
     *
     * A double that keeps the old tags when the document claims new ones
     * disagrees with production, which replaces the set. The double has to
     * hold the same contract in both directions: the claim replaces, the
     * absence leaves alone.
     */
    @Test
    fun `the double takes the claim when the document carries one`() = runTest {
        val repo = FakeTaskRepository()
        repo.upsert(TaskDocument.of(task(tags = listOf(TagId("tag-1")))))

        repo.upsert(
            TaskDocument(
                task = repo.tasks.value.getValue("t1"),
                tags = listOf(TagId("tag-2")),
                blockedBy = null,
            ),
        )

        assertEquals(
            listOf(TagId("tag-2")),
            repo.tasks.value.getValue("t1").tags,
            "the tags of the document are the tags of the task",
        )
    }

    /**
     * The dependencies are parts like the tags, and the double has to keep the
     * ones the document says nothing about.
     */
    @Test
    fun `the double keeps the dependencies the document does not carry`() = runTest {
        val repo = FakeTaskRepository()
        repo.upsert(TaskDocument.of(task(blockedBy = setOf(TaskId("t2")))))

        repo.upsert(
            TaskDocument(
                task = repo.tasks.value.getValue("t1"),
                tags = null,
                blockedBy = null,
            ),
        )

        assertEquals(
            setOf(TaskId("t2")),
            repo.tasks.value.getValue("t1").blockedBy,
            "the dependencies of the task are still the dependencies of the task",
        )
    }

    /**
     * The reader looks the parts up by their wire names, and the model writes
     * them under those names. A rename on one side only would leave the reader
     * looking for a key nobody sends — and the absence it would then see is
     * read as "the document says nothing", which is silent.
     *
     * The descriptor reports the *serial* names, so this check is what says
     * that the constant is the name on the wire. The other half — that the
     * reader looks the parts up by those names — is what the tests above pin:
     * a reader that looked up another name would find no key in a document the
     * model produced, and every "the document says what it carries" assertion
     * would fail.
     */
    @Test
    fun `the wire names the reader looks for are the names the model writes`() {
        val descriptor = Task.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()

        assertTrue(
            TAGS_WIRE_NAME in names,
            "the model writes the tags under the name the reader looks for",
        )
        assertTrue(
            BLOCKED_BY_WIRE_NAME in names,
            "the model writes the dependencies under the name the reader looks for",
        )
    }
}
