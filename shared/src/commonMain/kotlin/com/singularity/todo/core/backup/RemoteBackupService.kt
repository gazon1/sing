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
     * Uploads the archive at [localPath], encrypted under [password].
     *
     * What lands in the bucket is ciphertext: the archive is sealed on the device, and
     * the key is derived from the password, which the server never sees. The name of
     * the object says so — it carries [ENCRYPTED_SUFFIX].
     *
     * @param password the person's password, as an array. The call consumes it: the
     *   array is wiped before the call returns, so the secret does not sit in memory
     *   longer than the upload needs it.
     * @return a reference to the uploaded object, which [download] can use.
     */
    suspend fun upload(localPath: String, userId: UserId, password: CharArray): Result<String>

    /**
     * Downloads [remoteRef] to [destPath] and opens it under [password].
     *
     * The reference is not checked against the user's own prefix here: the bucket's
     * policy is what keeps one account's archive out of another's reach, and a check
     * on top of it would be a second opinion about what the policy allows. The
     * references this is called with come from [list], which asks for the user's own
     * prefix — so the path is the user's by the time it gets here.
     *
     * What is written to [destPath] is the archive itself, decrypted: the file on the
     * device is a zip again, and only the copy in the bucket is sealed.
     */
    suspend fun download(
        remoteRef: String,
        destPath: String,
        userId: UserId,
        password: CharArray,
    ): Result<Unit>

    /** The remote objects that belong to [userId]. */
    suspend fun list(userId: UserId): Result<List<BucketObject>>

    companion object {
        /**
         * The suffix an encrypted archive carries in the bucket.
         *
         * The name is not a secret — a bucket listing shows it — but it says what the
         * object is, and it is how the archive's own name is recovered when it comes
         * back: the local copy is the name without this suffix.
         */
        const val ENCRYPTED_SUFFIX = ".enc"

        /** The name the archive has on the device, from the reference it has in the bucket. */
        fun localFileName(remoteRef: String): String =
            remoteRef.substringAfterLast('/').removeSuffix(ENCRYPTED_SUFFIX)
    }
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
class RemoteBackupServiceImpl(
    private val bucket: BackupBucket,
    private val fs: FileSystem,
    private val archive: EncryptedArchive,
) : RemoteBackupService {

    override suspend fun upload(localPath: String, userId: UserId, password: CharArray): Result<String> =
        try {
            runCatchingCancellable {
                val plain = readArchive(localPath)
                val sealed = archive.seal(plain, password)
                val remotePath = remotePath(userId, localPath)
                bucket.upload(remotePath, sealed)
                remotePath
            }.recoverCatching { error ->
                throw classified(error, "the archive could not be uploaded")
            }
        } finally {
            password.fill('\u0000')
        }

    override suspend fun download(
        remoteRef: String,
        destPath: String,
        userId: UserId,
        password: CharArray,
    ): Result<Unit> = try {
        runCatchingCancellable {
            val sealed = bucket.download(remoteRef)
            val plain = archive.open(sealed, password)
            fs.writeBytes(destPath, plain)
        }.recoverCatching { error ->
            throw classified(error, "the archive could not be downloaded")
        }
    } finally {
        password.fill('\u0000')
    }

    override suspend fun list(userId: UserId): Result<List<BucketObject>> = runCatchingCancellable {
        bucket.list("${userId.value}/")
    }.recoverCatching { error ->
        throw classified(error, "the archives could not be listed")
    }

    /**
     * The archive's own bytes, read from the device.
     *
     * A failure here is local, and not a transport failure: the archive never
     * started its way to the bucket, so reporting it as "could not be uploaded"
     * names a cause that was never in play and buries the one that was — an
     * archive that is not on the disk any more is a different defect from a
     * bucket that refused the write, and the person reading the message has to be
     * able to tell them apart.
     */
    private suspend fun readArchive(localPath: String): ByteArray =
        runCatchingCancellable { fs.readBytes(localPath) }.getOrElse { error ->
            throw AppError.Persistence(
                message = "The archive at $localPath could not be read: ${error.message}",
                code = ARCHIVE_UNREADABLE_CODE,
                cause = error,
            )
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

        // The archive's own failures say what a person can do: the password does not
        // open it, the bytes are not an encrypted archive. Flattening them into
        // "the upload failed" would throw that away.
        is BackupError -> error

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
        "${userId.value}/${localPath.substringAfterLast('/')}${RemoteBackupService.ENCRYPTED_SUFFIX}"

    companion object {
        /** The code for the class that means "something else went wrong". */
        const val REJECTED_CODE = "backup.push_failed"

        /** The code for the class that means "the archive was not readable on this device". */
        const val ARCHIVE_UNREADABLE_CODE = "backup.archive_unreadable"
    }
}
