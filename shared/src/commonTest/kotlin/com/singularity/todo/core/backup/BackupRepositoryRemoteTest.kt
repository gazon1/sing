package com.singularity.todo.core.backup

import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.security.FakeArchiveCrypto
import com.singularity.todo.test.fakes.FakeDeviceKeyStore
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.Dispatchers
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
        private val downloadedEntries: List<ByteArray> = emptyList(),
    ) : RemoteBackupService {
        /** The paths the download was asked to write to, in order. */
        val downloadedTo = mutableListOf<String>()

        /** The refs the download was asked for, in order. */
        val downloadedRefs = mutableListOf<String>()

        /** The archives the upload was asked for, in order. */
        val uploaded = mutableListOf<RemoteArchive>()

        override suspend fun upload(
            archive: RemoteArchive,
            userId: UserId,
            password: CharArray,
        ): Result<String> {
            uploaded += archive
            return Result.success("$userId/${archive.name}")
        }

        override suspend fun uploadSealed(
            localPath: String,
            remoteName: String,
            userId: UserId,
        ): Result<String> = Result.success("$userId/$remoteName")

        override suspend fun download(
            remoteRef: String,
            destPath: String,
            userId: UserId,
            password: CharArray,
        ): Result<DownloadedArchive> {
            downloadedRefs += remoteRef
            downloadedTo += destPath
            downloadFailure?.let { return Result.failure(it) }
            // The download really writes the archive: the cleanup of a failed
            // import is only worth a test if there is a file to clean up.
            fs?.writeBytes(destPath, byteArrayOf(1, 2, 3))
            return Result.success(DownloadedArchive(basePath = destPath, entries = downloadedEntries))
        }

        override suspend fun list(userId: UserId): Result<List<BucketObject>> = Result.success(listed)
    }

    private class RecordingImporter(private val failure: Throwable? = null) : ArchiveImporter {
        /** The paths the import was asked to read, in order. */
        val importedFrom = mutableListOf<String>()

        /** The changes the import was asked to apply, in order. */
        val importedEntries = mutableListOf<List<ByteArray>>()

        override suspend fun import(options: ImportOptions): Result<RestoreResult> {
            importedFrom += options.sourcePath
            importedEntries += options.entries
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
        archive = EncryptedArchive(FakeArchiveCrypto()),
        deviceKeyStore = FakeDeviceKeyStore(EncryptedArchive(FakeArchiveCrypto())),
        cursors = BackupCursorStore(fs = fs, dir = backupDir),
        // The repository moves its work onto an IO dispatcher; the test hands in
        // an unconfined one so the call stays inside the test's own thread.
        ioDispatcher = Dispatchers.Unconfined,
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
     * The password does not survive the call — not even when the call failed early.
     *
     * The archive was not there to be encrypted, so the service never saw the
     * password; the repository is the one that has to wipe it. A secret that
     * survives a failed push is a secret that waits for the next attempt.
     */
    @Test
    fun `the password is wiped even when the archive is not there`() = runTest {
        val secret = "correct horse".toCharArray()

        val result = repository(MapFileSystem(), FakeRemoteBackupService(), RecordingImporter())
            .push(BackupId("b-1"), secret)

        assertTrue(result.isFailure, "there is no archive to push: $result")
        assertTrue(
            secret.all { it == '\u0000' },
            "the password must not survive the call: ${secret.concatToString()}",
        )
    }

    /**
     * A push carries the chain the archive belongs to.
     *
     * The base and the changes travel together, because a change says "on top
     * of that base" — a push that carried only the change would carry nothing
     * that could be restored. The name in the bucket is still the archive's own
     * name: it is the one the person pushed.
     */
    @Test
    fun `a push carries the base and the changes that follow the archive`() = runTest {
        val fs = MapFileSystem()
        fs.writeBytes("$backupDir/a.zip", byteArrayOf(1))
        fs.writeBytes("$backupDir/b.zip", byteArrayOf(2))
        fs.writeBytes("$backupDir/b.zip.delta", byteArrayOf(3))
        val cursors = BackupCursorStore(fs = fs, dir = backupDir)
        cursors.save(
            UserId("u-1"),
            BackupCursorState(
                cursor = 10L,
                baseArchivePath = "$backupDir/a.zip",
                entries = listOf(
                    BackupChainEntry(
                        archivePath = "$backupDir/b.zip",
                        deltaPath = "$backupDir/b.zip.delta",
                        byteSize = 1L,
                    ),
                ),
            ),
        )
        val remote = FakeRemoteBackupService()

        val result = repository(fs, remote, RecordingImporter()).push(BackupId("b"), "horse".toCharArray())

        assertTrue(result.isSuccess, "the push must succeed: $result")
        val pushed = remote.uploaded.single()
        assertEquals("$backupDir/a.zip", pushed.basePath, "the base of the chain is what travels")
        assertEquals(listOf("$backupDir/b.zip.delta"), pushed.entries, "the changes travel with the base")
        assertEquals("b.zip", pushed.name, "the object keeps the name of the archive that was pushed")
    }

    /**
     * A push of an archive that is not part of a chain carries that archive
     * alone.
     *
     * The bookkeeping can be behind the disk — a file that was deleted, a state
     * that was never written — and a push that failed over bookkeeping would be
     * a backup that did not happen.
     */
    @Test
    fun `a push of an archive outside a chain carries that archive alone`() = runTest {
        val fs = MapFileSystem()
        fs.writeBytes("$backupDir/lonely.zip", byteArrayOf(1))
        val remote = FakeRemoteBackupService()

        val result = repository(fs, remote, RecordingImporter())
            .push(BackupId("lonely"), "horse".toCharArray())

        assertTrue(result.isSuccess, "the push must succeed: $result")
        val pushed = remote.uploaded.single()
        assertEquals("$backupDir/lonely.zip", pushed.basePath)
        assertTrue(pushed.entries.isEmpty(), "there are no changes to carry")
    }

    /**
     * The changes that came down with the archive are handed to the importer.
     *
     * A download that brought back the base alone would restore an older state
     * and call it current — the base is what the chain started from, and the
     * changes are what the account looks like now.
     */
    @Test
    fun `the changes that came down are applied after the archive`() = runTest {
        val fs = MapFileSystem()
        val change = "the-change".encodeToByteArray()
        val remote = FakeRemoteBackupService(fs = fs, downloadedEntries = listOf(change))
        val importer = RecordingImporter()

        val result = repository(fs, remote, importer)
            .restoreFromCloud("u-1/singularity_backup_1.zip", "correct horse".toCharArray())

        assertTrue(result.isSuccess, "the restore must succeed: $result")
        assertEquals(listOf(listOf(change)), importer.importedEntries)
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
