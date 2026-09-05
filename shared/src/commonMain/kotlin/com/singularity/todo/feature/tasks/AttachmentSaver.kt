package com.singularity.todo.feature.tasks

/**
 * Port for saving file attachments associated with a task.
 * Abstracts over the platform-specific [AttachmentsViewModel.saveFileAttachment] call
 * so widget tests can substitute a no-op [FakeAttachmentSaver] without touching
 * the Compose runtime or file system.
 */
interface AttachmentSaver {
    suspend fun save(taskId: TaskId, sourcePath: String, mimeType: String?): Result<Unit>
}

/**
 * No-op implementation for tests.
 */
object FakeAttachmentSaver : AttachmentSaver {
    override suspend fun save(taskId: TaskId, sourcePath: String, mimeType: String?): Result<Unit> =
        Result.success(Unit)
}
