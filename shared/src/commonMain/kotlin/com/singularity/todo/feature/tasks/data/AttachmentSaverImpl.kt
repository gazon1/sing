package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.feature.tasks.domain.model.AttachmentSaver
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * [AttachmentSaver] implementation via [AttachmentRepository].
 * Lives in data layer so domain stays clean.
 */
class AttachmentSaverImpl(
    private val repository: AttachmentRepository,
) : AttachmentSaver {

    override suspend fun save(taskId: TaskId, path: String, mimeType: String?): Result<Unit> =
        repository.saveFileAttachment(
            taskId = taskId,
            sourcePath = path,
            mimeType = mimeType,
        ).map { }
}
