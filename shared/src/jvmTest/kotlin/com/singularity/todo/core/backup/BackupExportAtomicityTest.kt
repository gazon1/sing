package com.singularity.todo.core.backup

import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The export writes an archive, or nothing.
 *
 * ## What is at stake
 *
 * A backup directory is scanned for `.zip` files, and the newest one is what the
 * next scheduled push uploads. A file that is only half written looks exactly
 * like a backup to that scan — and it is the newest one, because it was written
 * last. So an export that fails half way used to leave behind the very file the
 * next push would seal into the cloud bucket as the person's newest backup.
 *
 * The archive is therefore written under a temporary name and moved into place
 * only when it is whole.
 */
@Tag("fast")
class BackupExportAtomicityTest {
    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-backup-atomic-").toFile() }
    private val tempDir: File by tempDirLazy

    @AfterTest
    fun tearDown() {
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    private val fs = JvmFileSystem()

    private val db = FakeAppDatabase()

    private val owner = UserId("atomic-user")

    private val backupDir: String get() = File(tempDir, "backups").absolutePath

    private fun exporter(fileSystem: FileSystem = fs) =
        BackupExporter(
            taskDao = db.taskDao(),
            noteDao = db.noteDao(),
            projectDao = db.projectDao(),
            tagDao = db.tagDao(),
            reminderDao = db.reminderDao(),
            projectReminderDao = db.projectReminderDao(),
            checklistDao = db.checklistDao(),
            tagGroupDao = db.tagGroupDao(),
            projectTagGroupDao = db.projectInheritedTagGroupDao(),
            savedSearchDao = db.savedSearchDao(),
            timeEntryDao = db.timeEntryDao(),
            profileDao = db.profileDao(),
            attachmentDao = db.attachmentDao(),
            annotationDao = db.annotationDao(),
            agendaViewDao = db.agendaViewDao(),
            codec = JvmBackupCodec(),
            clock = Clock.System,
            fs = fileSystem,
            cursors = BackupCursorStore(fs = fileSystem, dir = File(tempDir, "cursor").absolutePath),
            backupDir = backupDir,
        )

    /**
     * A failed export leaves no archive behind.
     *
     * The attachment is named by a path that is not on the disk, so the codec
     * fails while it is copying the file into the archive. The archive must be
     * gone afterwards: a `.zip` in the backup directory is a file the next
     * scheduled push would upload as the newest backup.
     */
    @Test
    fun `a failed export leaves no archive in the backup directory`() =
        runTest {
            db.attachmentDao().upsert(
                AttachmentEntity(
                    id = "missing-attachment",
                    taskId = "task-1",
                    userId = owner.value,
                    type = "File",
                    title = "gone.dat",
                    localPath = File(tempDir, "not-there.dat").absolutePath,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )

            val destPath = "$backupDir/atomic.zip"
            val result =
                exporter().export(
                    ExportOptions(userId = owner, destPath = destPath, includeAttachments = true),
                )

            assertTrue(result.isFailure, "the export must fail: the attachment is not on the disk")
            assertFalse(
                File(destPath).exists(),
                "the failed export left an archive behind, and the next push would upload it",
            )
            assertEquals(
                emptyList(),
                File(backupDir).listFiles()?.toList().orEmpty(),
                "the backup directory must hold no file at all after a failed export",
            )
        }

    /** A successful export leaves the archive — and nothing else. */
    @Test
    fun `a successful export leaves the archive and no temporary file`() =
        runTest {
            val destPath = "$backupDir/whole.zip"
            val result =
                exporter().export(
                    ExportOptions(userId = owner, destPath = destPath, includeAttachments = true),
                )

            assertTrue(result.isSuccess, "the export must succeed: ${result.exceptionOrNull()}")
            assertTrue(File(destPath).isFile, "the archive was not written")
            assertEquals(
                listOf("whole.zip"),
                File(backupDir).listFiles()?.map { it.name }.orEmpty(),
                "the backup directory must hold the archive and nothing else",
            )
        }

    /**
     * A destination the person picked on Android is a document, not a file.
     *
     * The Android file saver hands back a `content://` URI. There is no sibling
     * to write a temporary file next to and no rename to make, so the archive
     * goes straight into the document — a temporary file would be a path that
     * cannot exist, and an export that ends in "Export failed" is worse than
     * one that leaves a half-written document behind.
     */
    @Test
    fun `a document destination is written directly, with no temporary file`() =
        runTest {
            val mapFs = MapFileSystem()
            val destPath = "content://com.android.externalstorage.documents/document/primary:backup.zip"
            val result =
                exporter(mapFs).export(
                    ExportOptions(userId = owner, destPath = destPath, includeAttachments = true),
                )

            assertTrue(result.isSuccess, "the export must succeed: ${result.exceptionOrNull()}")
            assertEquals(
                listOf(destPath),
                mapFs.storedFiles().keys.toList(),
                "the archive must be written into the document itself, with no temporary file beside it",
            )
        }

    /**
     * A change that cannot be written leaves the previous one intact.
     *
     * The change that follows the snapshot is written under a temporary name
     * and moved over the real one, so a write that fails half way — a disk that
     * fills up — must leave the previous change as it was. Half a change is a
     * change that cannot be read, and a change that cannot be read costs the
     * chain.
     */
    @Test
    fun `a failed change write leaves the previous change intact`() =
        runTest {
            val destPath = "$backupDir/chain.zip"
            db.taskDao().upsert(task("t-1", "before"))
            exporter().export(ExportOptions(userId = owner, destPath = destPath))
            db.taskDao().upsert(task("t-1", "after"))
            exporter().export(ExportOptions(userId = owner, destPath = destPath))
            val change = File("$destPath.delta").readBytes()
            assertTrue(change.isNotEmpty(), "the second export must have written a change")

            db.taskDao().upsert(task("t-1", "later"))
            val result =
                exporter(failingChangeWrites(fs)).export(
                    ExportOptions(userId = owner, destPath = destPath),
                )

            assertTrue(result.isFailure, "the export must fail: the change cannot be written")
            assertContentEquals(
                change,
                File("$destPath.delta").readBytes(),
                "the previous change must survive a failed write",
            )
            assertFalse(
                File("$destPath.delta.tmp").exists(),
                "the failed change must leave no litter behind",
            )
        }

    /**
     * A filesystem whose change writes fail.
     *
     * The change goes through a temporary file, so the failure is injected
     * there: that is the write that would have left half a change behind.
     */
    private fun failingChangeWrites(delegate: FileSystem): FileSystem =
        object : FileSystem by delegate {
            override suspend fun writeBytes(
                path: String,
                data: ByteArray,
            ) {
                if (path.endsWith(".delta.tmp")) {
                    throw IllegalStateException("the disk is full")
                }
                delegate.writeBytes(path, data)
            }
        }

    /** The task, as the database holds it. */
    private fun task(
        id: String,
        title: String,
    ) = TaskEntity(
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
}
