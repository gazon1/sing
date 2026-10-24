package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId

/**
 * The remote half of the backup feature: where an archive goes when the user
 * pushes it to the cloud, and where it comes back from.
 *
 * ## Why this is an interface
 *
 * The app has to answer "can this device push a backup anywhere?" before the
 * user presses the button, and the answer is different on a device with a
 * Supabase project and on one without. A port lets the repository ask that
 * question and lets a test answer it without a network.
 *
 * The implementation that talks to Supabase Storage lives in
 * [SupabaseStorageRemoteBackupService]; nothing here knows the vendor exists.
 */
interface RemoteBackupService {
    /**
     * Uploads the archive at [localPath].
     *
     * @return a reference to the uploaded object, which [download] can use.
     */
    suspend fun upload(localPath: String, userId: UserId): Result<String>

    /** Downloads [remoteRef] to [destPath]. */
    suspend fun download(remoteRef: String, destPath: String, userId: UserId): Result<Unit>

    /** The remote objects that belong to [userId]. */
    suspend fun list(userId: UserId): Result<List<String>>
}

/** The remote half could not do its job, with a reason a user can act on. */
class RemoteBackupException(message: String) : Exception(message)
