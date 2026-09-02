package com.singularity.todo.core.attachments

/**
 * Interface for uploading attachments to remote storage.
 * Currently stubbed — real Supabase Storage integration comes in Phase 11.
 */
interface AttachmentUploadService {
    suspend fun upload(attachment: Attachment): Result<String>
    suspend fun download(remotePath: String, targetPath: String): Result<Unit>
}

/**
 * Stub implementation — no-op. Returns the local path as the "remote" URL.
 */
class StubAttachmentUploadService : AttachmentUploadService {
    override suspend fun upload(attachment: Attachment): Result<String> =
        Result.success(attachment.localPath ?: "")

    override suspend fun download(remotePath: String, targetPath: String): Result<Unit> =
        Result.success(Unit)
}
