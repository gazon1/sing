package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentType
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeAttachmentRepositoryTest {

    private val userId = UserId("test-user")
    private val testAttachment = Attachment(
        id = AttachmentId("attachment-1"),
        taskId = TaskId("task-1"),
        userId = userId,
        type = AttachmentType.File,
        localPath = "/tmp/file.txt",
        mimeType = "text/plain",
        createdAt = Clock.now(),
        updatedAt = Clock.now(),
    )

    @Test
    fun `saveFileAttachmentOverride returns injected failure`() = runTest {
        val repo = FakeAttachmentRepository()
        repo.saveFileAttachmentOverride = Result.failure(IllegalStateException("injected"))

        val result = repo.saveFileAttachment(
            taskId = TaskId("task-1"),
            sourcePath = "/tmp/file.txt",
            mimeType = "text/plain",
        )

        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertEquals("injected", result.exceptionOrNull()?.message)
    }

    @Test
    fun `override cleared falls through to runCatching success`() = runTest {
        val repo = FakeAttachmentRepository()

        repo.saveFileAttachmentOverride = Result.failure(IllegalStateException("injected"))
        repo.saveFileAttachmentOverride = null  // clear

        val result = repo.saveFileAttachment(
            taskId = TaskId("task-1"),
            sourcePath = "/tmp/file.txt",
            mimeType = "text/plain",
        )

        assertTrue(result.isSuccess)
    }
}
