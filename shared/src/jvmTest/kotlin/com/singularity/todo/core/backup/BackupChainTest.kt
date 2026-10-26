package com.singularity.todo.core.backup

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.security.FakeArchiveCrypto
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.time.Clock

/**
 * The chain: a base and the changes that follow it.
 *
 * ## What these tests are about
 *
 * The chain is a promise about the account: "the snapshot plus the changes is
 * the account as it was when the last export ran". Every part of that promise
 * can be broken in a way that is invisible until a restore is wrong — a change
 * that carries more than it should, a removal that is silently dropped, a chain
 * that grows without end, a push that carries the base but not the change.
 *
 * So the tests drive the whole way round: export, export again, push, restore.
 *
 * ## Why the cryptography is faked
 *
 * The real cipher is a platform implementation and cannot be reached from a
 * common test; what matters here is that the service *seals* what it uploads
 * and *opens* what it downloads, and the container's own round trip against the
 * real AES-GCM is proved elsewhere.
 */
@Tag("fast")
class BackupChainTest {

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-backup-chain-").toFile() }
    private val tempDir: File by tempDirLazy

    private val backupDir: String get() = File(tempDir, "backups").absolutePath

    private val fs = JvmFileSystem()

    private val sourceDb = FakeAppDatabase()

    private val targetDb = FakeAppDatabase()

    private val owner = UserId("chain-user")

    private val cursors = BackupCursorStore(fs = fs, dir = File(tempDir, "cursor").absolutePath)

    @AfterTest
    fun tearDown() {
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    /**
     * The change carries the rows that changed — and nothing else.
     *
     * The rows that did not change are still in the base, and a change that
     * carried them would be a snapshot wearing a different name.
     */
    @Test
    fun `the change carries the rows that changed and nothing else`() = runTest {
        seedTask("t-1", "before")
        seedTask("t-2", "untouched")
        export("a.zip")
        renameTask("t-1", "after")

        export("b.zip")

        val change = readChange("b.zip")
        assertEquals(listOf("t-1"), change.payload.tasks.map { it.id }, "only the changed row travels")
        assertEquals("after", change.payload.tasks.single().title)
    }

    /**
     * The first export writes no change at all.
     *
     * There is nothing before it, so the snapshot it wrote is the beginning of
     * the chain and there is no change to carry.
     */
    @Test
    fun `the first export writes no change`() = runTest {
        seedTask("t-1", "before")

        export("a.zip")

        assertFalse(File("$backupDir/a.zip.delta").exists(), "there is nothing before the first export")
    }

    /**
     * A removal re-seals the base instead of writing a change.
     *
     * A change is a set of rows, and a row that is gone is not a row: the only
     * way to say "this is not here any more" is to write the whole snapshot
     * again. So the chain starts again from the snapshot that has the removal in
     * it.
     */
    @Test
    fun `a removal starts the chain again instead of writing a change`() = runTest {
        seedTask("t-1", "before")
        export("a.zip")
        sourceDb.taskDao().deleteAllForUser(owner.value)

        export("b.zip")

        assertFalse(
            File("$backupDir/b.zip.delta").exists(),
            "a removal cannot travel in a change, so no change is written",
        )
        val state = cursors.load(owner)
        assertEquals("$backupDir/b.zip", state.baseArchivePath, "the chain starts again from the snapshot")
        assertTrue(state.entries.isEmpty(), "the old chain's changes are dropped")
    }

    /**
     * A chain past its bound starts again.
     *
     * A chain that only grows is a restore that gets slower every time it runs,
     * so the number of changes is bounded — and the export that passes the bound
     * writes a fresh base instead.
     */
    @Test
    fun `a chain past its bound starts again`() = runTest {
        seedTask("t-1", "before")
        val exports = ArchiveFormat.MAX_CHAIN_ENTRIES + 2
        repeat(exports) { index ->
            renameTask("t-1", "title-$index")
            export("archive-$index.zip")
        }

        val state = cursors.load(owner)
        assertTrue(
            state.entries.size <= ArchiveFormat.MAX_CHAIN_ENTRIES,
            "the chain must not grow past its bound: ${state.entries.size}",
        )
        assertEquals(
            "$backupDir/archive-${exports - 1}.zip",
            state.baseArchivePath,
            "the last snapshot is the base of the chain that started again",
        )
    }

    /**
     * A push carries the chain, and a restore brings back the merged account.
     *
     * The push seals the base and the change together; the restore opens them
     * and applies the change on top of the base. What the second database holds
     * afterwards is the account as it was when the change was written — which is
     * not what the base alone says.
     */
    @Test
    fun `a push carries the chain and a restore brings back the merged account`() = runTest {
        seedTask("t-1", "before")
        export("a.zip")
        renameTask("t-1", "after")
        export("b.zip")

        val bucket = InMemoryBucket()
        val repository = repository(bucket)
        val pushed = repository.push(BackupId.fromPath("$backupDir/b.zip"), "horse".toCharArray())
        assertTrue(pushed.isSuccess, "the push must succeed: ${pushed.exceptionOrNull()}")

        val restored = repository.restoreFromCloud(pushed.getOrThrow(), "horse".toCharArray())
        assertTrue(restored.isSuccess, "the restore must succeed: ${restored.exceptionOrNull()}")

        val tasks = targetDb.taskDao().listAllForUser(owner.value)
        assertEquals(1, tasks.size, "the restored database holds ${tasks.size} tasks")
        assertEquals("after", tasks.single().title, "the change must be applied on top of the base")
        assertEquals(
            1,
            restored.getOrThrow().entityCounts.tasks,
            "the restore reports the merged account, not the base",
        )
    }

    /**
     * An archive from before the chain existed restores exactly as it always did.
     *
     * No changes, no merged state to speak of: the base is the account, and the
     * restore says so.
     */
    @Test
    fun `an archive without changes restores exactly as before`() = runTest {
        seedTask("t-1", "before")
        export("a.zip")

        val restored = importer().import(
            ImportOptions(sourcePath = "$backupDir/a.zip", targetUserId = owner),
        )

        assertTrue(restored.isSuccess, "the restore must succeed: ${restored.exceptionOrNull()}")
        val tasks = targetDb.taskDao().listAllForUser(owner.value)
        assertEquals(1, tasks.size)
        assertEquals("before", tasks.single().title)
        assertEquals(1, restored.getOrThrow().entityCounts.tasks)
    }

    /**
     * A change that says nothing is not written.
     *
     * An export of an account that did not move writes no change: a change with
     * nothing in it would only make the chain longer than the account.
     */
    @Test
    fun `an export of an account that did not move writes no change`() = runTest {
        seedTask("t-1", "before")
        export("a.zip")

        export("b.zip")

        assertFalse(File("$backupDir/b.zip.delta").exists(), "there was nothing to change")
    }

    /** Writes a task into the source database. */
    private suspend fun seedTask(id: String, title: String) {
        sourceDb.taskDao().upsert(task(id, title))
    }

    /** Changes the title of a task — a row that changed. */
    private suspend fun renameTask(id: String, title: String) {
        sourceDb.taskDao().upsert(task(id, title))
    }

    /** The task, as the database holds it. */
    private fun task(id: String, title: String) = TaskEntity(
        id = id,
        title = title,
        description = null,
        projectId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        completedAt = null,
        archivedAt = null,
        createdAt = 1L,
        updatedAt = 1L,
        userId = owner.value,
    )

    /** Exports the source database into the backup directory. */
    private suspend fun export(name: String) {
        val result = exporter().export(
            ExportOptions(userId = owner, destPath = "$backupDir/$name"),
        )
        assertTrue(result.isSuccess, "the export must succeed: ${result.exceptionOrNull()}")
    }

    /** The change that was written beside the snapshot. */
    private suspend fun readChange(name: String): BackupDelta {
        val bytes = fs.readBytes("$backupDir/$name.delta")
        return StableJson.decodeFromString(BackupDelta.serializer(), bytes.decodeToString())
    }

    /** The exporter, writing into the backup directory. */
    private fun exporter() = BackupExporter(
        taskDao = sourceDb.taskDao(),
        noteDao = sourceDb.noteDao(),
        projectDao = sourceDb.projectDao(),
        tagDao = sourceDb.tagDao(),
        reminderDao = sourceDb.reminderDao(),
        projectReminderDao = sourceDb.projectReminderDao(),
        checklistDao = sourceDb.checklistDao(),
        tagGroupDao = sourceDb.tagGroupDao(),
        projectTagGroupDao = sourceDb.projectInheritedTagGroupDao(),
        savedSearchDao = sourceDb.savedSearchDao(),
        timeEntryDao = sourceDb.timeEntryDao(),
        profileDao = sourceDb.profileDao(),
        attachmentDao = sourceDb.attachmentDao(),
        annotationDao = sourceDb.annotationDao(),
        agendaViewDao = sourceDb.agendaViewDao(),
        codec = JvmBackupCodec(),
        clock = Clock.System,
        fs = fs,
        cursors = cursors,
    )

    /** The importer, writing into the target database. */
    private fun importer() = BackupImporter(
        log = Logger.withTag("BackupChainTest"),
        taskDao = targetDb.taskDao(),
        noteDao = targetDb.noteDao(),
        projectDao = targetDb.projectDao(),
        tagDao = targetDb.tagDao(),
        reminderDao = targetDb.reminderDao(),
        projectReminderDao = targetDb.projectReminderDao(),
        checklistDao = targetDb.checklistDao(),
        tagGroupDao = targetDb.tagGroupDao(),
        projectTagGroupDao = targetDb.projectInheritedTagGroupDao(),
        savedSearchDao = targetDb.savedSearchDao(),
        timeEntryDao = targetDb.timeEntryDao(),
        profileDao = targetDb.profileDao(),
        agendaViewDao = targetDb.agendaViewDao(),
        attachmentDao = targetDb.attachmentDao(),
        annotationDao = targetDb.annotationDao(),
        attachmentStorage = AttachmentStorage(fs, File(tempDir, "attachments").absolutePath),
        codec = JvmBackupCodec(),
        clock = Clock.System,
        createFileSource = JvmFileSourceFactory(),
    )

    /** The repository, with a bucket that keeps what was uploaded. */
    private fun repository(bucket: BackupBucket) = BackupRepositoryImpl(
        exporter = exporter(),
        importer = importer(),
        remoteService = RemoteBackupServiceImpl(
            bucket = bucket,
            fs = fs,
            archive = EncryptedArchive(FakeArchiveCrypto()),
            ioDispatcher = Dispatchers.Unconfined,
        ),
        fs = fs,
        backupDir = backupDir,
        currentUser = FakeProfileAwareCurrentUser(initialUserId = owner),
        clock = Clock.System,
        cursors = cursors,
        ioDispatcher = Dispatchers.Unconfined,
    )

    /**
     * A bucket that keeps the objects in memory.
     *
     * The push and the restore have to talk to the same bucket, so the bytes
     * that went up are the bytes that come down — which is the whole point of
     * the round trip.
     */
    private class InMemoryBucket : BackupBucket {
        private val objects = mutableMapOf<String, ByteArray>()

        override suspend fun upload(path: String, source: ByteSource, sizeBytes: Long) {
            objects[path] = readAll(source)
        }

        override suspend fun download(path: String, sink: ByteSink) {
            val bytes = objects[path] ?: throw NoSuchElementException("no object at $path")
            sink.write(bytes, 0, bytes.size)
        }

        override suspend fun list(prefix: String): List<BucketObject> =
            objects.keys
                .filter { it.startsWith(prefix) }
                .map {
                    BucketObject(
                        path = it,
                        updatedAtEpochMillis = 0L,
                        sizeBytes = objects.getValue(it).size.toLong(),
                    )
                }
    }
}

/** Everything the source yields, read in pieces. */
private fun readAll(source: ByteSource): ByteArray {
    var collected = ByteArray(0)
    val buffer = ByteArray(1024)
    while (true) {
        val read = source.read(buffer, 0, buffer.size)
        if (read < 0) break
        collected += buffer.copyOfRange(0, read)
    }
    return collected
}
