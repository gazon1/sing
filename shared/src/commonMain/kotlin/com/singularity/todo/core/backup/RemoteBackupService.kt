package com.singularity.todo.core.backup

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.transportError
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

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
     * Uploads a container that is **already sealed**, under the name [remoteName].
     *
     * ## Why this call exists
     *
     * The ordinary [upload] seals the archive here, under the person's password —
     * the password is the key, and the server never has it. A **scheduled** export
     * has no person and therefore no password: it seals the archive under the
     * device key, and the device key travels in the container wrapped under the
     * password. The sealing is done by whoever holds the key, so this call only
     * moves the bytes.
     *
     * The name in the bucket is [remoteName] and not the name of the file the
     * container happens to sit in: the sealed container is a working file, and a
     * working file's name is not the archive's name.
     */
    suspend fun uploadSealed(localPath: String, remoteName: String, userId: UserId): Result<String>

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
 *
 * ## Why the work happens on another thread
 *
 * Sealing an archive is slow on purpose: the key derivation is 210 000 rounds of
 * PBKDF2, and the cipher then walks the whole archive. On a phone that is seconds
 * of arithmetic, and the caller of this class is the ViewModel — so without a
 * change the screen the person is looking at stops answering while their backup is
 * being encrypted.
 *
 * So the work is moved to [ioDispatcher] before it starts. The dispatcher is a
 * constructor parameter rather than a constant, so a test can hand in a thread of
 * its own and see *where* the work ran — the alternative is a test that asserts
 * the work is fast, which passes on a fast machine and fails on a slow one.
 */
class RemoteBackupServiceImpl(
    private val bucket: BackupBucket,
    private val fs: FileSystem,
    private val archive: EncryptedArchive,
    private val ioDispatcher: CoroutineDispatcher,
) : RemoteBackupService {

    override suspend fun upload(localPath: String, userId: UserId, password: CharArray): Result<String> =
        try {
            withContext(ioDispatcher) {
                runCatchingCancellable {
                    val remotePath = remotePath(userId, localPath)
                    val sealed = workingPath(localPath, SEALED_SUFFIX)
                    try {
                        sealInto(localPath, sealed, password)
                        val size = sealedSize(sealed)
                        val source = fs.openRead(sealed)
                        try {
                            bucket.upload(remotePath, source, size)
                        } finally {
                            source.close()
                        }
                    } finally {
                        fs.delete(sealed)
                    }
                    remotePath
                }.recoverCatching { error ->
                    throw classified(error, "the archive could not be uploaded")
                }
            }
        } finally {
            password.fill('\u0000')
        }

    override suspend fun uploadSealed(
        localPath: String,
        remoteName: String,
        userId: UserId,
    ): Result<String> =
        withContext(ioDispatcher) {
            runCatchingCancellable {
                val remotePath = "${userId.value}/$remoteName${RemoteBackupService.ENCRYPTED_SUFFIX}"
                val source = fs.openRead(localPath)
                try {
                    bucket.upload(remotePath, source, sealedSize(localPath))
                } finally {
                    source.close()
                }
                remotePath
            }.recoverCatching { error ->
                throw classified(error, "the archive could not be uploaded")
            }
        }

    override suspend fun download(
        remoteRef: String,
        destPath: String,
        userId: UserId,
        password: CharArray,
    ): Result<Unit> = try {
        withContext(ioDispatcher) {
            runCatchingCancellable {
                // Three files, not one: what came down, what came out of it, and
                // the archive itself. The middle one exists because the cipher
                // cannot tell a wrong password from a right one until it has
                // read everything — so the plaintext is written to a temporary
                // file and only copied over the destination once the tag has
                // matched. A wrong password must not replace a good archive
                // with rubbish.
                val sealed = workingPath(destPath, SEALED_SUFFIX)
                val plain = workingPath(destPath, PLAIN_SUFFIX)
                try {
                    val sink = fs.openWrite(sealed)
                    try {
                        bucket.download(remoteRef, sink)
                    } finally {
                        sink.close()
                    }
                    val source = fs.openRead(sealed)
                    val target = openTarget(source, plain)
                    try {
                        archive.open(source, target, password)
                    } finally {
                        target.close()
                        source.close()
                    }
                    copy(plain, destPath)
                } finally {
                    fs.delete(sealed)
                    fs.delete(plain)
                }
            }.recoverCatching { error ->
                throw classified(error, "the archive could not be downloaded")
            }
        }
    } finally {
        password.fill('\u0000')
    }

    /**
     * The archive, sealed, written to [sealedPath].
     *
     * The archive is never held in memory: it is read from the device in pieces
     * and encrypted in pieces, and the sealed form lands on the disk before it
     * goes anywhere. The disk is cheaper than the heap, and the heap is what
     * runs out on a phone.
     */
    private suspend fun sealInto(localPath: String, sealedPath: String, password: CharArray) {
        val source = openArchive(localPath)
        val sink = openTarget(source, sealedPath)
        try {
            archive.seal(source, sink, password)
        } finally {
            sink.close()
            source.close()
        }
    }

    /**
     * The file to write into, opened for [source].
     *
     * The source is opened first, so opening the sink can fail — and a source that
     * was opened and never closed is a file nobody can delete. So the sink opens
     * with the source in hand, and the source is closed if it does not.
     */
    private suspend fun openTarget(source: ByteSource, path: String): ByteSink {
        var opened = false
        try {
            return fs.openWrite(path).also { opened = true }
        } finally {
            // The sink did not open, so there is nothing to hand back — and a source
            // that was opened and never closed is a file nobody can delete. Nothing
            // is caught here: the failure — a cancellation above all — keeps its own
            // type and reaches the caller as itself, and the close happens anyway.
            if (!opened) source.close()
        }
    }

    /**
     * How many bytes the sealed archive has.
     *
     * The bucket wants the size before the first byte arrives — it is what the
     * request is lengthened with. A sealed archive that cannot be measured is
     * not an archive that can be uploaded, and reporting "zero bytes" would
     * send an empty object to the bucket and call it a push.
     */
    private suspend fun sealedSize(path: String): Long =
        fs.stat(path)?.sizeBytes ?: throw AppError.Persistence(
            message = "The sealed archive at $path could not be measured",
            code = ARCHIVE_UNREADABLE_CODE,
        )

    /**
     * The archive's own source, opened for reading.
     *
     * A failure here is local, and not a transport failure: the archive never
     * started its way to the bucket, so reporting it as "could not be uploaded"
     * names a cause that was never in play and buries the one that was — an
     * archive that is not on the disk any more is a different defect from a
     * bucket that refused the write, and the person reading the message has to
     * be able to tell them apart.
     */
    private suspend fun openArchive(localPath: String): ByteSource =
        runCatchingCancellable { fs.openRead(localPath) }.getOrElse { error ->
            throw AppError.Persistence(
                message = "The archive at $localPath could not be read: ${error.message}",
                code = ARCHIVE_UNREADABLE_CODE,
                cause = error,
            )
        }

    /** One file copied over another, in pieces. */
    private suspend fun copy(fromPath: String, toPath: String) {
        val source = fs.openRead(fromPath)
        val sink = openTarget(source, toPath)
        try {
            val buffer = ByteArray(COPY_CHUNK_BYTES)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
        } finally {
            sink.close()
            source.close()
        }
    }

    /**
     * The path of a working file next to [path].
     *
     * The working files live beside the archive they belong to, so that they are
     * on the same volume and are removed with it — and they are named apart
     * from the archives, so a leftover is never mistaken for one.
     */
    private fun workingPath(path: String, suffix: String): String = "$path$suffix"

    override suspend fun list(userId: UserId): Result<List<BucketObject>> = runCatchingCancellable {
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
        /** The suffix of the working file that holds the sealed archive. */
        private const val SEALED_SUFFIX = ".sealed"

        /** The suffix of the working file that holds the opened archive. */
        private const val PLAIN_SUFFIX = ".plain"

        /** How much of the archive is copied at a time. */
        private const val COPY_CHUNK_BYTES = 64 * 1024

        /** The code for the class that means "something else went wrong". */
        const val REJECTED_CODE = "backup.push_failed"

        /** The code for the class that means "the archive was not readable on this device". */
        const val ARCHIVE_UNREADABLE_CODE = "backup.archive_unreadable"
    }
}
