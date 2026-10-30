package com.singularity.todo.feature.attachments

import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentType
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

@Tag("fast")
class AttachmentsViewModelTest {

    @Test
    fun `delete emits undo event and restore brings attachment back`() = runTest {
        val repo = FakeAttachmentRepository()
        val taskId = TaskId("t1")
        val attachment = Attachment(
            id = AttachmentId.fromString("a1"),
            taskId = taskId,
            userId = UserId("test-user"),
            type = AttachmentType.Url,
            url = "https://example.com",
            title = "Example",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        repo.seed(attachment)

        val vm = AttachmentsViewModel(
            repository = repo,
            taskId = taskId,
            scope = com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
        )
        val events = mutableListOf<AttachmentsUiEvent>()
        val job = backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()

        vm.onIntent(AttachmentsIntent.Delete(attachment.id))
        runCurrent()
        assertTrue(events.any { it is AttachmentsUiEvent.UndoDeleteAttachment })

        vm.onIntent(AttachmentsIntent.Restore(attachment.id))
        runCurrent()

        job.cancel()
    }
}
