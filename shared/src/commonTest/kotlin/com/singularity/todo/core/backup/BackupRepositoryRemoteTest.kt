package com.singularity.todo.core.backup

import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The repository's remote half, tested at the repository's level.
 *
 * ## Why this level and not the service's
 *
 * The service above the bucket knows the path and the error classes; the bucket
 * knows the vendor. Neither of them knows what the repository does with the two:
 * that a ref becomes a name, that the archive lands in the backup directory, and
 * that an archive which could not be imported does not stay there.
 *
 * Those three are the repository's decisions, and until the exporter and the
 * importer were reachable through ports, the repository could not be tested at
 * all — which is why this file exists rather than three more cases in the
 * service's test.
 */
@Tag("fast")
class BackupRepositoryRemoteTest {

    private val backupDir = "/backups"

    private class FixedClock(private val instant: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)) :
        Clock {
        override fun now(): Instant = instant
    }

    private class FakeRemoteBackupService(
        private val fs: FileSystem? = null,
        private val listed: List<BucketObject> = emptyList(),
        private val downloadFailure: Throwable? = null,
    ) : RemoteBackupService {
        /** The paths the download was asked to write to, in order. */
        val downloadedTo = mutableListOf<String>()

        /** The refs the download was asked for, in order. */
        val downloadedRefs = mutableListOf<String>()

        override suspend fun upload(localPath: String, userId: UserId, password: CharArray): Result<String> =
            Result.success("$userId/${localPath.substringAfterLast('/')}")

        override suspend fun download(
            remoteRef: String,
            destPath: String,
            userId: UserId,
            password: CharArray,
        ): Result<Unit> {
            downloadedRefs += remoteRef
            downloadedTo += destPath
            downloadFailure?.let { return Result.failure(it) }
            // The download really writes the archive: the cleanup of a failed
            // import is only worth a test if there is a file to clean up.
            fs?.writeBytes(destPath, byteArrayOf(1, 2, 3))
            return Result.success(Unit)
        }

        override suspend fun list(userId: UserId): Result<List<BucketObject>> = Result.success(listed)
    }

    private class RecordingImporter(private val failure: Throwable? = null) : ArchiveImporter {
        /** The paths the import was asked to read, in order. */
        val importedFrom = mutableListOf<String>()

        override suspend fun import(options: ImportOptions): Result<RestoreResult> {
            importedFrom += options.sourcePath
            failure?.let { return Result.failure(it) }
            return Result.success(
                RestoreResult(
                    manifest = BackupManifest(
                        formatVersion = BackupFormat.FORMAT_VERSION,
                        appName = BackupFormat.APP_NAME,
                        appVersion = "1",
                        createdAtEpochMillis = 0L,
                        userIdHash = "hash",
                        schemaVersion = BackupFormat.SCHEMA_VERSION,
                        entityCounts = EntityCounts(),
                        payloadChecksum = "checksum",
                    ),
                    entityCounts = EntityCounts(),
                    restoredAttachmentCount = 0,
                    missingAttachmentIds = emptyList(),
                ),
            )
        }
    }

    private class UnusedExporter : ArchiveExporter {
        override suspend fun export(options: ExportOptions): Result<BackupResult> =
            error("the exporter is not used on the remote paths")
    }

    private fun repository(
        fs: FileSystem,
        remote: RemoteBackupService,
        importer: ArchiveImporter,
    ) = BackupRepositoryImpl(
        exporter = UnusedExporter(),
        importer = importer,
        remoteService = remote,
        fs = fs,
        backupDir = backupDir,
        currentUser = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1")),
        clock = FixedClock(),
    )

    /**
     * The list the screen shows has to say which archive is which.
     *
     * A ref is the bucket's business and a name is the person's; the date and the
     * size are what tell two archives apart when both are called
     * "singularity_backup.zip".
     */
    @Test
    fun `the cloud list reports the name, the date and the size`() = runTest {
        val remote = FakeRemoteBackupService(
            listed = listOf(
                BucketObject(
                    path = "u-1/singularity_backup_1.zip${RemoteBackupService.ENCRYPTED_SUFFIX}",
                    updatedAtEpochMillis = 1_700_000_000_000,
                    sizeBytes = 4096,
                ),
            ),
        )

        val result = repository(MapFileSystem(), remote, RecordingImporter()).listRemote()

        val backup = result.getOrThrow().single()
        assertEquals(
            "u-1/singularity_backup_1.zip${RemoteBackupService.ENCRYPTED_SUFFIX}",
            backup.ref,
            "the ref is what the download takes",
        )
        assertEquals(
            "singularity_backup_1.zip",
            backup.name,
            "the name is what the person reads — the sealed suffix is the bucket's business",
        )
        assertEquals(1_700_000_000_000, backup.updatedAtEpochMillis, "the bucket knows when it was written")
        assertEquals(4096, backup.sizeBytes, "the bucket knows how big it is")
    }

    /**
     * The restore lands in the backup directory, under the ref's own name.
     *
     * The repository owns that directory: a caller that named the destination
     * could aim the download anywhere, and the archive would sit where the list
     * of the backups does not look for it.
     */
    @Test
    fun `a restore from the cloud lands in the backup directory and is imported`() = runTest {
        val fs = MapFileSystem()
        val remote = FakeRemoteBackupService(fs = fs)
        val importer = RecordingImporter()

        val result = repository(fs, remote, importer)
            .restoreFromCloud("u-1/singularity_backup_1.zip", "correct horse".toCharArray())

        assertTrue(result.isSuccess, "the restore must succeed: $result")
        assertEquals(listOf("$backupDir/singularity_backup_1.zip"), remote.downloadedTo)
        assertEquals(listOf("$backupDir/singularity_backup_1.zip"), importer.importedFrom)
    }

    /**
     * An archive that could not be imported does not stay in the backup directory.
     *
     * It would sit in the list of the backups this person can restore from, as if
     * it were one of theirs — and it is a file nobody asked for, fetched by a
     * restore that failed.
     */
    @Test
    fun `an archive that could not be imported does not stay in the backup directory`() = runTest {
        val fs = MapFileSystem()
        val remote = FakeRemoteBackupService(fs = fs)
        val importer = RecordingImporter(failure = IllegalStateException("the archive is not a zip"))

        val result = repository(fs, remote, importer)
            .restoreFromCloud("u-1/singularity_backup_1.zip", "correct horse".toCharArray())

        assertTrue(result.isFailure, "a failed import must be reported: $result")
        assertFalse(
            fs.exists("$backupDir/singularity_backup_1.zip"),
            "the fetched archive must not be left behind",
        )
    }

    /**
     * A download that fails is reported, and nothing is imported.
     *
     * The import of a file that was never fetched would fail with a different
     * error, and the person would be told the archive was broken rather than
     * that the bucket refused to serve it.
     */
    @Test
    fun `a failed download is reported and nothing is imported`() = runTest {
        val remote = FakeRemoteBackupService(downloadFailure = IllegalStateException("the bucket refused"))
        val importer = RecordingImporter()

        val result = repository(MapFileSystem(), remote, importer)
            .restoreFromCloud("u-1/singularity_backup_1.zip", "correct horse".toCharArray())

        assertTrue(result.isFailure, "a failed download must be reported: $result")
        assertTrue(importer.importedFrom.isEmpty(), "nothing may be imported without the archive")
    }
}
