package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId

/**
 * Interface for remote backup storage.
 * Currently stubbed — real Supabase Storage integration comes in Phase 12.
 */
interface RemoteBackupService {
    suspend fun upload(localPath: String, userId: UserId): Result<String>
    suspend fun download(remoteRef: String, destPath: String, userId: UserId): Result<Unit>
    suspend fun list(userId: UserId): Result<List<String>>
}

class StubRemoteBackupService : RemoteBackupService {
    override suspend fun upload(localPath: String, userId: UserId): Result<String> =
        Result.success(localPath) // Return local path as pseudo-remote URL

    override suspend fun download(remoteRef: String, destPath: String, userId: UserId): Result<Unit> =
        Result.success(Unit) // Stub: remoteRef is local path

    override suspend fun list(userId: UserId): Result<List<String>> = Result.success(emptyList())
}
