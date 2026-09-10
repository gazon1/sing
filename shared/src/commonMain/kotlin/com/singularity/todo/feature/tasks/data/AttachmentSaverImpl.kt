package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.AttachmentSaver
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * [AttachmentSaver] implementation via [AttachmentRepository].
 * Lives in data layer so domain stays clean.
 */
class AttachmentSaverImpl(
    private val repository: AttachmentRepository,
    private val currentUser: ProfileAwareCurrentUser,
) : AttachmentSaver {

    override suspend fun save(taskId: TaskId, path: String, mimeType: String?): Result<Unit> {
        return repository.saveFileAttachment(
            taskId = taskId,
            userId = currentUser.current,
            sourcePath = path,
            mimeType = mimeType,
        ).map { }
    }
}
