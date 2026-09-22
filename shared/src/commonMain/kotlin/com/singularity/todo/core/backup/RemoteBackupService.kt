package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId

/**
 * Stub implementation of remote backup storage — no-op.
 * Real Supabase Storage integration comes in Phase 12.
 */
class StubRemoteBackupService {
    suspend fun upload(localPath: String, userId: UserId): Result<String> =
        Result.success(localPath) // Return local path as pseudo-remote URL

    suspend fun download(remoteRef: String, destPath: String, userId: UserId): Result<Unit> =
        Result.success(Unit) // Stub: remoteRef is local path

    suspend fun list(userId: UserId): Result<List<String>> = Result.success(emptyList())
}
