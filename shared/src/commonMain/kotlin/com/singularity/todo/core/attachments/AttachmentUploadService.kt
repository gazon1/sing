package com.singularity.todo.core.attachments

/**
 * Stub implementation of attachment upload — no-op.
 * Real Supabase Storage integration comes in Phase 11.
 * Returns the local path as the "remote" URL.
 */
class StubAttachmentUploadService {
    suspend fun upload(attachment: Attachment): Result<String> = Result.success(attachment.localPath ?: "")

    suspend fun download(remotePath: String, targetPath: String): Result<Unit> = Result.success(Unit)
}
