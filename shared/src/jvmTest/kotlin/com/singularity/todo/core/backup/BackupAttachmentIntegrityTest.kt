package com.singularity.todo.core.backup

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.time.Clock

/**
 * A restored attachment is checked against what the archive says about it.
 *
 * ## What is at stake
 *
 * The payload carries the size and the checksum of every attachment, and both
 * were left unused: an entry that arrived truncated was written to the disk and
 * counted as restored. The person then had a file that is not the file they
 * attached — with nothing in the restore report to say so.
 *
 * So "present" is not "intact": the entry is checked against the row that
 * describes it before it is allowed to replace what is on the disk.
 */
@Tag("fast")
class BackupAttachmentIntegrityTest {

    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-attachment-integrity-").toFile() }
    private val tempDir: File by tempDirLazy

    @AfterTest
    fun tearDown() {
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    private val fs = JvmFileSystem()

    private val sourceDb = FakeAppDatabase()

    private val targetDb = FakeAppDatabase()

    private val owner = UserId("integrity-user")

    private val attachmentDir: String get() = File(tempDir, "attachments").absolutePath

    private val attachmentFile = File(attachmentDir, "task-1/att-1.plain")

    @Test
    fun `a truncated attachment is reported missing and does not replace the file on disk`() = runTest {
        val content = "the bytes of the attachment".toByteArray()
        val source = File(tempDir, "source.dat")
        source.parentFile.mkdirs()
        source.writeBytes(content)

        sourceDb.attachmentDao().upsert(
            AttachmentEntity(
                id = "att-1",
                taskId = "task-1",
                userId = owner.value,
                type = "File",
                title = "source.dat",
                localPath = source.absolutePath,
                fileSizeBytes = content.size.toLong(),
                mimeType = "text/plain",
                checksum = BackupDomain.sha256Hex(content),
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val archive = File(tempDir, "archive.zip")
        val exported = exporter().export(
            ExportOptions(userId = owner, destPath = archive.absolutePath, includeAttachments = true),
        )
        assertTrue(exported.isSuccess, "the export failed: ${exported.exceptionOrNull()}")

        // The archive that reaches the restore carries half of the attachment.
        val truncated = File(tempDir, "truncated.zip")
        truncateAttachmentEntry(archive, truncated)

        // The file that is already on the disk: a restore must not replace it
        // with the damaged one.
        attachmentFile.parentFile.mkdirs()
        attachmentFile.writeText("the file that was already there")

        val restored = importer().import(
            ImportOptions(sourcePath = truncated.absolutePath, targetUserId = owner),
        )
        assertTrue(restored.isSuccess, "the import failed: ${restored.exceptionOrNull()}")

        val result = restored.getOrThrow()
        assertEquals(
            listOf("att-1"),
            result.missingAttachmentIds,
            "the truncated attachment must be reported as missing",
        )
        assertEquals(
            0,
            result.restoredAttachmentCount,
            "a truncated attachment must not be counted as restored",
        )
        assertEquals(
            "the file that was already there",
            attachmentFile.readText(),
            "the damaged entry must not replace the file that was on the disk",
        )
        assertNull(
            targetDb.attachmentDao().getById("att-1", owner.value)?.localPath,
            "the row must not point at a file that was never restored",
        )
    }

    /** Copies the archive, halving the bytes of every attachment entry. */
    private fun truncateAttachmentEntry(source: File, target: File) {
        ZipInputStream(source.inputStream()).use { zis ->
            ZipOutputStream(target.outputStream()).use { zos ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val bytes = zis.readBytes()
                    val data =
                        if (entry.name.startsWith(BackupFormat.DIR_ATTACHMENTS)) {
                            bytes.copyOf(bytes.size / 2)
                        } else {
                            bytes
                        }
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(data)
                    zos.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    /** The exporter, writing the archive under test. */
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
        cursors = BackupCursorStore(fs = fs, dir = File(tempDir, "cursor").absolutePath),
        backupDir = tempDir.absolutePath,
    )

    /** The importer, writing into the empty target database. */
    private fun importer() = BackupImporter(
        log = Logger(loggerConfigInit(CommonWriter()), "backup-attachment-integrity"),
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
        attachmentStorage = AttachmentStorage(fs, attachmentDir),
        codec = JvmBackupCodec(),
        clock = Clock.System,
        createFileSource = JvmFileSourceFactory(),
        tasksSettings = FakeTasksSettingsRepository(),
    )
}
