package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId

/**
 * Port for bulk-restoring a [BackupPayload] into a target user account.
 *
 * This is the proper port shape for the [BackupImporter] layer exception — it takes an
 * explicit [targetUserId] rather than going through ambient-profile-scoped repositories,
 * bypassing their [com.singularity.todo.core.repository.assertCanWrite] guards.
 *
 * Unlike repository writes, this:
 * - Takes an explicit [targetUserId] rather than the ambient profile user
 * - Writes DAOs directly (the DAO's `WHERE user_id = :userId` clause is the enforcement)
 * - Does **not** call [com.singularity.todo.core.sync.SyncRepository.enqueue] — the sync shadow
 *   mechanism picks up the restored rows on the next normal sync cycle via the `updatedAt`
 *   timestamps written by [restore]
 *
 * @see BackupImporter — the historical layer exception this port replaces
 */
interface BulkImportPort {
    /**
     * Restores all entities from [payload] into [targetUserId].
     *
     * [attachmentData] is a map of `"{id}.dat"` → raw bytes, sourced from the backup zip
     * (keyed by attachment ID with `.dat` extension). When [overwriteExisting] is `true`
     * (the default), existing rows are upserted.
     *
     * The [manifest] is passed separately (not embedded in [payload]) because it is
     * read and validated before the restore loop and is the authoritative record of
     * what the archive contained.
     *
     * Returns a [RestoreResult] describing what was restored, or a failure if any step
     * of the restore loop threw.
     */
    suspend fun restore(
        payload: BackupPayload,
        manifest: BackupManifest,
        targetUserId: UserId,
        attachmentData: Map<String, ByteArray>,
        overwriteExisting: Boolean = true,
    ): Result<RestoreResult>
}
