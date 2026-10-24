package com.singularity.todo.core.backup

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remote half of the backup feature, and the two decisions it makes.
 *
 * ## What was untested, and why it mattered
 *
 * The class that talks to Storage had no test of its own — there was a test on the
 * port and a test on the ViewModel with a fake, and the class in between had none.
 * That class is where the archive's *path* is decided and where the bucket's
 * failure is *named*, and both of those are the things that can hurt a user: a
 * path that is not under the user's own id shows one account another's archives,
 * and one code for four failures hides "sign in again" among them.
 *
 * ## Why the bucket is faked
 *
 * The bucket's own calls sit behind [BackupBucket], and the vendor's types
 * in [SupabaseBackupBucket]. The decisions above that line are what these
 * tests are about, and they can be driven without a network.
 */
@Tag("fast")
class RemoteBackupServiceImplTest {

    /**
     * A bucket that records what it was asked for.
     *
     * A failure can be armed, so the four classes of failure can be driven one by
     * one — the bucket's answers are what the classification reads.
     */
    private class FakeBucket : BackupBucket {
        /** The uploads, by path. */
        val uploads = mutableMapOf<String, ByteArray>()

        /** The prefix the last listing asked for. */
        var listedPrefix: String? = null
            private set

        /** What the bucket answers with, when it is armed to fail. */
        var failure: Throwable? = null

        /** The bytes a download answers with. */
        var downloadBytes: ByteArray = ByteArray(0)

        override suspend fun upload(path: String, bytes: ByteArray) {
            failure?.let { throw it }
            uploads[path] = bytes
        }

        override suspend fun download(path: String): ByteArray {
            failure?.let { throw it }
            return downloadBytes
        }

        override suspend fun list(prefix: String): List<BucketObject> {
            failure?.let { throw it }
            listedPrefix = prefix
            return emptyList()
        }
    }

    private fun service(bucket: BackupBucket, files: MapFileSystem = MapFileSystem()) =
        RemoteBackupServiceImpl(bucket = bucket, fs = files)

    /**
     * The archive the upload is about, already on disk.
     *
     * The service reads the file before it talks to the bucket, so a test that arms
     * the bucket has to have an archive to read — otherwise the failure it meant to
     * provoke never happens, and the test passes for the wrong reason.
     */
    private suspend fun archive(files: MapFileSystem, name: String = "a.zip"): MapFileSystem {
        files.writeBytes("/backups/$name", "the-archive".encodeToByteArray())
        return files
    }

    /**
     * The archive goes under the user's own id.
     *
     * The bucket is shared by every account of the project, so the prefix is what
     * keeps one person's archives out of another person's list — whatever the
     * bucket's own policies do.
     */
    @Test
    fun `the archive goes under the user's own id`() = runTest {
        val bucket = FakeBucket()
        val files = MapFileSystem()
        files.writeBytes("/backups/archive.zip", "the-archive".encodeToByteArray())

        val result = service(bucket, files).upload("/backups/archive.zip", UserId("user-1"))

        assertTrue(result.isSuccess, "the upload must succeed: $result")
        assertEquals("user-1/archive.zip", result.getOrNull())
        assertEquals(
            "the-archive",
            bucket.uploads["user-1/archive.zip"]?.decodeToString(),
            "the bytes that were uploaded must be the archive's own",
        )
    }

    /** The listing asks for the user's own prefix, and nothing else. */
    @Test
    fun `the listing asks for the user's own prefix`() = runTest {
        val bucket = FakeBucket()

        val result = service(bucket).list(UserId("user-1"))

        assertTrue(result.isSuccess, "the listing must succeed: $result")
        assertEquals("user-1/", bucket.listedPrefix)
    }

    /** The download lands in the file the caller named. */
    @Test
    fun `the download lands in the file the caller named`() = runTest {
        val bucket = FakeBucket()
        bucket.downloadBytes = "the-archive".encodeToByteArray()
        val files = MapFileSystem()

        val result = service(bucket, files).download("user-1/a.zip", "/backups/a.zip", UserId("user-1"))

        assertTrue(result.isSuccess, "the download must succeed: $result")
        assertEquals(
            "the-archive",
            files.readBytes("/backups/a.zip").decodeToString(),
            "the downloaded bytes must reach the file",
        )
    }

    /**
     * A refused session says so.
     *
     * "The session is gone" and "the policy refused the write" used to arrive as
     * one code, and the difference between them is the difference between signing
     * in again and giving up.
     */
    @Test
    fun `a refused session is reported as unauthorized`() = runTest {
        val bucket = FakeBucket()
        bucket.failure = TransportException(TransportFailure.UNAUTHORIZED, "HTTP 401")

        val result = service(bucket, archive(MapFileSystem())).upload("/backups/a.zip", UserId("user-1"))

        val error = result.exceptionOrNull()
        assertIs<AppError>(error, "the failure must be reported as a project error: $error")
        assertEquals("backup.unauthorized", error.code)
    }

    /** An object that is not there is not there — not "something else went wrong". */
    @Test
    fun `a missing archive is reported as not found`() = runTest {
        val bucket = FakeBucket()
        bucket.failure = TransportException(TransportFailure.MISSING, "HTTP 404")

        val result = service(bucket).download("user-1/a.zip", "/backups/a.zip", UserId("user-1"))

        val error = result.exceptionOrNull()
        assertIs<AppError>(error, "the failure must be reported as a project error: $error")
        assertEquals("backup.not_found", error.code)
    }

    /** A call that did not arrive is worth a retry, and says so. */
    @Test
    fun `a lost connection is reported as a network failure`() = runTest {
        val bucket = FakeBucket()
        bucket.failure = TransportException(TransportFailure.NETWORK, "the socket gave up")

        val result = service(bucket, archive(MapFileSystem())).upload("/backups/a.zip", UserId("user-1"))

        val error = result.exceptionOrNull()
        assertIs<AppError>(error, "the failure must be reported as a project error: $error")
        assertEquals("backup.network_failed", error.code)
    }

    /**
     * Everything else keeps the name the dashboard already groups by.
     *
     * `backup.push_failed` is the code the reports carry today, and splitting it
     * into four would split one defect into four.
     */
    @Test
    fun `anything else keeps the code the dashboard groups by`() = runTest {
        val bucket = FakeBucket()
        bucket.failure = TransportException(TransportFailure.REJECTED, "HTTP 403")

        val result = service(bucket, archive(MapFileSystem())).upload("/backups/a.zip", UserId("user-1"))

        val error = result.exceptionOrNull()
        assertIs<AppError>(error, "the failure must be reported as a project error: $error")
        assertEquals("backup.push_failed", error.code)
    }

    /**
     * A bucket that is not configured keeps its own message.
     *
     * "Add your Supabase project in the Account settings" is better advice than
     * "the server refused the request", and the classification must not overwrite
     * it.
     */
    @Test
    fun `a bucket that is not configured keeps its own message`() = runTest {
        val bucket = FakeBucket()
        bucket.failure = RemoteBackupException(
            "No cloud storage is configured. Add a Supabase project in the Account settings first.",
        )

        val result = service(bucket, archive(MapFileSystem())).upload("/backups/a.zip", UserId("user-1"))

        val error = result.exceptionOrNull()
        assertIs<RemoteBackupException>(error, "the project's own failure must survive: $error")
        assertTrue(
            error.message?.contains("No cloud storage is configured") == true,
            "the message must name what to do: ${error.message}",
        )
    }

    /**
     * An archive that cannot be read is not uploaded, and it is not reported as an
     * upload failure either.
     *
     * The archive never started its way to the bucket, so "could not be uploaded"
     * names a cause that was never in play and buries the one that was. The code
     * says what happened — a local read, not a refused write.
     */
    @Test
    fun `an archive that cannot be read is not uploaded`() = runTest {
        val bucket = FakeBucket()

        val result = service(bucket).upload("/backups/missing.zip", UserId("user-1"))

        assertTrue(result.isFailure, "a missing archive must not report success")
        assertTrue(bucket.uploads.isEmpty(), "nothing must have been uploaded")
        assertNull(result.getOrNull())
        val error = result.exceptionOrNull()
        assertIs<AppError>(error, "the failure must be an app error: $error")
        assertEquals(
            "backup.archive_unreadable",
            error.code,
            "a local read that failed is not an upload that was refused",
        )
    }
}
