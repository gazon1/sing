package com.singularity.todo.feature.tasks

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.fakeSyncDocumentWriter
import com.singularity.todo.feature.tags.TagId
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

@Tag("fast")
class TaskDocumentRoundTripTest {
    private class RecordingTaskRepository : FakeTaskRepository() {
        val documents = mutableListOf<TaskDocument>()
        override suspend fun upsert(document: TaskDocument): Task {
            documents += document
            return super.upsert(document)
        }
    }

    private fun task(
        id: String = "t1",
        tags: List<TagId> = listOf(TagId("tag-1")),
        blockedBy: Set<TaskId> = setOf(TaskId("t2")),
    ) = Task(
        id = TaskId(id),
        title = "Pay the rent",
        description = "desc",
        userId = UserId("alice"),
        tags = tags,
        blockedBy = blockedBy,
        createdAt = Instant.fromEpochMilliseconds(1_000),
        updatedAt = Instant.fromEpochMilliseconds(2_000),
    )

    @Test
    fun `the document that leaves is the document that arrives`() = runTest {
        val repo = RecordingTaskRepository()
        val writer = fakeSyncDocumentWriter(tasks = repo)
        val original = task()

        val document = original.toJson() as JsonObject

        writer.upsert(DocType.Task, document)
        val received = repo.documents.single()

        assertEquals(original.title, received.task.title)
        assertEquals(original.tags, received.tags)
        assertEquals(original.blockedBy, received.blockedBy)
        assertEquals(original.updatedAt, received.task.updatedAt)
    }
}
