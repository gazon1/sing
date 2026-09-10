package com.singularity.todo.feature.tasks.domain.model

/**
 * Port interface for saving attachments to task.
 * Implementation is provided by AttachmentsViewModel (via AttachmentsViewModelAttachmentSaver).
 */
interface AttachmentSaver {
    suspend fun save(taskId: TaskId, path: String, mimeType: String?): Result<Unit>
}
