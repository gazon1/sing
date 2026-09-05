package com.singularity.todo.feature.tasks

import com.singularity.todo.feature.attachments.AttachmentsViewModel

/**
 * Production [AttachmentSaver] that delegates to [AttachmentsViewModel].
 * This avoids a circular dependency where [AttachmentsViewModel] is needed
 * to construct [TaskEditorViewModel].
 */
class AttachmentsViewModelAttachmentSaver(
    private val getAttachmentsViewModel: () -> AttachmentsViewModel,
) : AttachmentSaver {
    override suspend fun save(taskId: TaskId, sourcePath: String, mimeType: String?): Result<Unit> {
        return runCatching {
            getAttachmentsViewModel().saveFileAttachment(taskId, sourcePath, mimeType)
        }
    }
}
