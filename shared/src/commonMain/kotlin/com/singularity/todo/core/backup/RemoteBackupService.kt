package com.singularity.todo.core.backup

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.transportError
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.ids.UserId

/**
 * The remote half of the backup feature: where an archive goes when the user
 * pushes it to the cloud, and where it comes back from.
 *
 * ## Why this is an interface
 *
 * The app has to answer "can this device push a backup anywhere?" before the user
 * presses the button, and the answer is different on a device with a Supabase
 * project and on one without. A port lets the repository ask that question and
 * lets a test answer it without a network.
 *
 * The implementation that talks to Supabase Storage lives in
 * [RemoteBackupServiceImpl]; nothing here knows the vendor exists.
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

/**
 * The remote half of the backup feature, over a bucket.
 *
 * ## What this class decides
 *
 * Two things, and both of them are decisions the user can be hurt by:
 *
 * - **where** an archive goes: under the user's own id, so a listing for one
 *   account never shows another account's archives whatever the bucket's policies
 *   are;
 * - **what a failure means**: the bucket's policies are the reason a push can fail
 *   in four different ways, and the difference between them is the difference
 *   between "sign in again", "the bucket does not exist", "there is no connection"
 *   and "the policy refused this write". The first is a session problem, the second
 *   a deployment problem, and they used to arrive as one line: `backup.push_failed`.
 *
 * ## Why the bucket is behind a port
 *
 * Because both decisions are worth testing, and neither can be tested while the
 * class talks to a bucket itself. [BackupBucket] is the narrow slice of the bucket
 * that these decisions need; the vendor's own calls live in
 * [SupabaseBackupBucket], which is one of the files declared in
 * `VendorSdkConfinementTest`.
 *
 * ## Why the bucket is not created by the app
 *
 * Creating a bucket needs the service-role key, and an app that carries a
 * service-role key is an app that can read every row of the database. The bucket is
 * therefore something the project's owner creates once — and the failure when it is
 * missing says so, rather than reporting a generic error.
 */
class RemoteBackupServiceImpl(private val bucket: BackupBucket, private val fs: FileSystem) : RemoteBackupService {

    override suspend fun upload(localPath: String, userId: UserId): Result<String> =
        runCatchingCancellable {
            val bytes = fs.readBytes(localPath)
            val remotePath = remotePath(userId, localPath)
            bucket.upload(remotePath, bytes)
            remotePath
        }.recoverCatching { error ->
            throw classified(error, "the archive could not be uploaded")
        }

    override suspend fun download(
        remoteRef: String,
        destPath: String,
        userId: UserId,
    ): Result<Unit> = runCatchingCancellable {
        val bytes = bucket.download(remoteRef)
        fs.writeBytes(destPath, bytes)
    }.recoverCatching { error ->
        throw classified(error, "the archive could not be downloaded")
    }

    override suspend fun list(userId: UserId): Result<List<String>> = runCatchingCancellable {
        bucket.list("${userId.value}/")
    }.recoverCatching { error ->
        throw classified(error, "the archives could not be listed")
    }

    /**
     * The failure as the project reports it.
     *
     * The vendor's failure is classified at the seam, so the code the crash
     * reporter groups by says which of the four happened.
     */
    private fun classified(error: Throwable, what: String): Throwable = when (error) {
        is TransportException -> transportError(
            area = "backup",
            failure = error.failure,
            message = error.message ?: what,
            cause = error,
            rejectedCode = REJECTED_CODE,
        )

        // The project already said what is wrong: no cloud storage is configured.
        // Classifying it as a network failure would be a worse message than the
        // one it has.
        is RemoteBackupException -> error

        is AppError -> error

        else -> transportError(
            area = "backup",
            failure = TransportFailure.REJECTED,
            message = what,
            cause = error,
            rejectedCode = REJECTED_CODE,
        )
    }

    private fun remotePath(userId: UserId, localPath: String): String =
        "${userId.value}/${localPath.substringAfterLast('/')}"

    companion object {
        /** The code for the class that means "something else went wrong". */
        const val REJECTED_CODE = "backup.push_failed"
    }
}
