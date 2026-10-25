package com.singularity.todo.core.backup

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.TransportException
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.security.FakeArchiveCrypto
import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
 *
 * ## And why the cryptography is faked too
 *
 * The real cipher is a platform implementation and cannot be reached from
 * `commonTest`; the round trip against the real AES-GCM is proved by
 * `JvmArchiveCryptoTest`, on the JVM. What matters here is that the service
 * *seals* what it uploads and *opens* what it downloads — that the bytes that
 * reach the bucket are not the archive, and that a wrong password does not
 * produce a file.
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

    /**
     * A fresh password for every call.
     *
     * The service consumes the password it is given — it wipes the array before it
     * returns — so a password held in a field would be zeroes by the second test
     * that used it. The secret is short-lived by design, and the test has to
     * respect that.
     */
    private fun password(): CharArray = "correct horse".toCharArray()

    private fun service(
        bucket: BackupBucket,
        files: MapFileSystem = MapFileSystem(),
        archive: EncryptedArchive = EncryptedArchive(FakeArchiveCrypto()),
    ) = RemoteBackupServiceImpl(bucket = bucket, fs = files, archive = archive)

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
     * The archive goes under the user's own id, and its name says it is sealed.
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

        val result = service(bucket, files).upload("/backups/archive.zip", UserId("user-1"), password())

        assertTrue(result.isSuccess, "the upload must succeed: $result")
        assertEquals("user-1/archive.zip${RemoteBackupService.ENCRYPTED_SUFFIX}", result.getOrNull())
        assertTrue(
            bucket.uploads.containsKey("user-1/archive.zip${RemoteBackupService.ENCRYPTED_SUFFIX}"),
            "the object that was uploaded must carry the sealed name: ${bucket.uploads.keys}",
        )
    }

    /**
     * The bytes that reach the bucket are not the archive.
     *
     * This is the feature: the object in the bucket is ciphertext, and the key is
     * the person's password, which the server never sees.
     */
    @Test
    fun `what reaches the bucket is not the archive in the clear`() = runTest {
        val bucket = FakeBucket()
        val files = MapFileSystem()
        files.writeBytes("/backups/archive.zip", "the-archive".encodeToByteArray())

        val result = service(bucket, files).upload("/backups/archive.zip", UserId("user-1"), password())

        assertTrue(result.isSuccess, "the upload must succeed: $result")
        val uploaded = bucket.uploads.values.single()
        assertNotEquals(
            "the-archive",
            uploaded.decodeToString(),
            "the object in the bucket must not be the archive in the clear",
        )
        assertTrue(
            uploaded.copyOfRange(0, EncryptedArchive.MAGIC.length).decodeToString() == EncryptedArchive.MAGIC,
            "the object in the bucket must say what it is",
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

    /**
     * The download lands in the file the caller named — and the file is the
     * archive again, not the ciphertext.
     *
     * The device holds a zip; only the copy in the bucket is sealed.
     */
    @Test
    fun `the download lands in the file the caller named`() = runTest {
        val bucket = FakeBucket()
        val archive = EncryptedArchive(FakeArchiveCrypto())
        bucket.downloadBytes = archive.seal("the-archive".encodeToByteArray(), password())
        val files = MapFileSystem()

        val result = service(bucket, files, archive)
            .download("user-1/a.zip", "/backups/a.zip", UserId("user-1"), password())

        assertTrue(result.isSuccess, "the download must succeed: $result")
        assertContentEquals(
            "the-archive".encodeToByteArray(),
            files.readBytes("/backups/a.zip"),
            "the downloaded bytes must reach the file as the archive",
        )
    }

    /**
     * The password does not survive the call.
     *
     * The secret is wiped by the service that consumed it, so a failed upload does
     * not leave it lying in memory for the next attempt — and a person who has to
     * try again types it again.
     */
    @Test
    fun `the password is wiped before the call returns`() = runTest {
        val bucket = FakeBucket()
        val files = MapFileSystem()
        files.writeBytes("/backups/archive.zip", "the-archive".encodeToByteArray())
        val secret = password()

        service(bucket, files).upload("/backups/archive.zip", UserId("user-1"), secret)

        assertTrue(
            secret.all { it == '\u0000' },
            "the password must not survive the call: ${secret.concatToString()}",
        )
    }

    /**
     * A wrong password writes nothing.
     *
     * The file that lands on the device has to be the archive; a failed opening
     * must not leave a file that looks like one.
     */
    @Test
    fun `a wrong password writes no file`() = runTest {
        val bucket = FakeBucket()
        val archive = EncryptedArchive(FakeArchiveCrypto())
        bucket.downloadBytes = archive.seal("the-archive".encodeToByteArray(), password())
        val files = MapFileSystem()

        val result = service(bucket, files, archive)
            .download("user-1/a.zip", "/backups/a.zip", UserId("user-1"), "wrong horse".toCharArray())

        assertTrue(result.isFailure, "a wrong password must be reported: $result")
        assertFalse(files.exists("/backups/a.zip"), "no file may be left behind")
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

        val result = service(bucket, archive(MapFileSystem())).upload("/backups/a.zip", UserId("user-1"), password())

        val error = result.exceptionOrNull()
        assertIs<AppError>(error, "the failure must be reported as a project error: $error")
        assertEquals("backup.unauthorized", error.code)
    }

    /** An object that is not there is not there — not "something else went wrong". */
    @Test
    fun `a missing archive is reported as not found`() = runTest {
        val bucket = FakeBucket()
        bucket.failure = TransportException(TransportFailure.MISSING, "HTTP 404")

        val result = service(bucket).download("user-1/a.zip", "/backups/a.zip", UserId("user-1"), password())

        val error = result.exceptionOrNull()
        assertIs<AppError>(error, "the failure must be reported as a project error: $error")
        assertEquals("backup.not_found", error.code)
    }

    /** A call that did not arrive is worth a retry, and says so. */
    @Test
    fun `a lost connection is reported as a network failure`() = runTest {
        val bucket = FakeBucket()
        bucket.failure = TransportException(TransportFailure.NETWORK, "the socket gave up")

        val result = service(bucket, archive(MapFileSystem())).upload("/backups/a.zip", UserId("user-1"), password())

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

        val result = service(bucket, archive(MapFileSystem())).upload("/backups/a.zip", UserId("user-1"), password())

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

        val result = service(bucket, archive(MapFileSystem())).upload("/backups/a.zip", UserId("user-1"), password())

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

        val result = service(bucket).upload("/backups/missing.zip", UserId("user-1"), password())

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
