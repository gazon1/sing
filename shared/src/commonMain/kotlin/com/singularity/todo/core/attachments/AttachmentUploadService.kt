package com.singularity.todo.core.attachments

/**
 * Stub implementation of attachment upload — no-op.
 * Real Supabase Storage integration comes in Phase 11.
 *
 * [upload] returns the local path unchanged, not a remote URL — the caller
 * should treat this as a placeholder and must not assume the returned string
 * is reachable over a network.
 */
class StubAttachmentUploadService {
    suspend fun upload(attachment: Attachment): Result<String> = Result.success(attachment.localPath ?: "")

    suspend fun download(remotePath: String, targetPath: String): Result<Unit> = Result.success(Unit)
}
