@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.backup

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.backup.BackupBucket
import com.singularity.todo.core.backup.BackupCursorStore
import com.singularity.todo.core.backup.BackupExporter
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.BackupRepositoryImpl
import com.singularity.todo.core.backup.BucketObject
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.EncryptedArchive
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.backup.RemoteBackupServiceImpl
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.security.FakeArchiveCrypto
import com.singularity.todo.core.settings.SettingsExporter
import com.singularity.todo.core.settings.SettingsImporter
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeDeviceKeyStore
import com.singularity.todo.test.fakes.FakeFileSharePort
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Where the archive goes decides what follows it.
 *
 * ## What these tests are about
 *
 * The backup screen writes an archive in two ways, and both of them go through
 * the same ViewModel call — [BackupIntent.CreateBackupTo]:
 *
 * - a **bare file name** — "write it where the app keeps its backups" — which the
 *   repository resolves into the app's own backup directory;
 * - a **destination the user picked** — the system save dialog handed back a
 *   `content://` document or a path of their choosing.
 *
 * The first one is part of the chain: the change that follows the snapshot is
 * written beside it, and a restore reads the snapshot plus the changes. The
 * second one is a snapshot and nothing else — the chain belongs to the app's own
 * directory, and a file the user put on their own desktop cannot carry it.
 *
 * So the two destinations are not the same request with a different path: one of
 * them writes a change, the other must not. The difference was broken once
 * already (#1442 — the exporter re-read the *resolved* path, which always looks
 * like a user-picked one, and the change was never written again). These tests
 * are the net under that.
 *
 * ## Why the whole stack is here
 *
 * The decision is not the ViewModel's: the ViewModel forwards the destination,
 * the repository resolves it and the exporter decides about the change. A test
 * with a fake repository could only say that a string was forwarded, so it says
 * nothing about the change. So the real repository and the real exporter are
 * wired here, over a real filesystem, and the assertions are about the files
 * that are (or are not) on the disk.
 */
@Tag("fast")
class BackupViewModelCreateToTest {
    private val tempDirLazy = lazy { Files.createTempDirectory("singularity-backup-create-to-").toFile() }
    private val tempDir: File by tempDirLazy

    /** Where the app keeps its own backups — the destination the chain lives in. */
    private val backupDir: String get() = File(tempDir, "backups").absolutePath

    /** A destination the user picked — outside the app's own directory. */
    private val pickedDir: String get() = File(tempDir, "picked").absolutePath

    private val fs = JvmFileSystem()

    private val db = FakeAppDatabase()

    private val owner = UserId("create-to-user")

    private val cursors = BackupCursorStore(fs = fs, dir = File(tempDir, "cursor").absolutePath)

    @AfterTest
    fun tearDown() {
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    /**
     * A bare file name lands in the app's backup directory, and the change that
     * follows the snapshot is written beside it.
     */
    @Test
    fun `a bare file name is written into the backup directory and the change follows it`() =
        runTest {
            seedTask("t-1", "before")
            val vm = createVm()

            vm.onIntent(BackupIntent.CreateBackupTo("a.zip"))
            testScheduler.runCurrent()
            testScheduler.runCurrent()

            assertTrue(
                File("$backupDir/a.zip").isFile,
                "the archive the person asked for must be in the app's own backup directory",
            )
            assertEquals(
                "$backupDir/a.zip",
                vm.stateFlow.value.lastBackup?.destPath,
                "the screen has to say where the archive went",
            )

            renameTask("t-1", "after")

            vm.onIntent(BackupIntent.CreateBackupTo("b.zip"))
            testScheduler.runCurrent()
            testScheduler.runCurrent()

            assertTrue(
                File("$backupDir/b.zip.delta").isFile,
                "the change that follows the snapshot has to be written beside it — " +
                    "without it the chain is broken and a restore loses everything since the base",
            )
        }

    /**
     * A destination the user picked gets the snapshot and nothing else.
     *
     * The chain belongs to the app's own directory: the file the user put
     * somewhere of their own choosing cannot carry the change, and a change
     * written beside it would travel away from the backup it follows.
     */
    @Test
    fun `a destination the user picked gets the snapshot and no change`() =
        runTest {
            seedTask("t-1", "before")
            val vm = createVm()
            val picked = File(pickedDir, "chosen.zip").absolutePath

            vm.onIntent(BackupIntent.CreateBackupTo(picked))
            testScheduler.runCurrent()
            testScheduler.runCurrent()

            assertTrue(File(picked).isFile, "the archive must land where the user asked for it")
            assertEquals(picked, vm.stateFlow.value.lastBackup?.destPath, "the screen has to say where it went")

            renameTask("t-1", "after")

            vm.onIntent(BackupIntent.CreateBackupTo(File(pickedDir, "chosen-again.zip").absolutePath))
            testScheduler.runCurrent()
            testScheduler.runCurrent()

            assertFalse(
                File("$picked.delta").exists(),
                "a file the user chose cannot carry the change — the change belongs to the chain in the backup directory",
            )
            assertFalse(
                File(File(pickedDir, "chosen-again.zip").absolutePath + ".delta").exists(),
                "the second export to the same kind of destination must not write a change either",
            )
            assertTrue(
                deltaFilesIn(backupDir).isEmpty(),
                "a user-chosen destination must not put a change into the app's backup directory either: " +
                    deltaFilesIn(backupDir),
            )
        }

    // ─── fixture ──────────────────────────────────────────────────────────────

    /** The ViewModel, wired to the real repository and the real exporter. */
    private fun createVm(): BackupViewModel =
        BackupViewModel(
            repository = repository(),
            authRepository = FakeAuthRepository(Session.Anonymous(owner)),
            backupFileNamer = DefaultBackupFileNamer { _ -> "backup.zip" },
            clock = Clock.System,
            settingsExporter = stubSettingsExporter,
            settingsImporter = stubSettingsImporter,
            fileSourceFactory = JvmFileSourceFactory(),
            fileSharePort = FakeFileSharePort(),
            scope = testScope(backgroundScope),
        )

    /** The repository: the real one, over the real filesystem. */
    private fun repository() =
        BackupRepositoryImpl(
            exporter = exporter(),
            importer = importer(),
            remoteService =
                RemoteBackupServiceImpl(
                    bucket = UnusedBucket(),
                    fs = fs,
                    archive = EncryptedArchive(FakeArchiveCrypto()),
                    ioDispatcher = Dispatchers.Unconfined,
                ),
            fs = fs,
            backupDir = backupDir,
            currentUser = FakeProfileAwareCurrentUser(initialUserId = owner),
            clock = Clock.System,
            deviceKeyStore = FakeDeviceKeyStore(EncryptedArchive(FakeArchiveCrypto())),
            cursors = cursors,
            ioDispatcher = Dispatchers.Unconfined,
        )

    /** The exporter, writing where the repository says the archive goes. */
    private fun exporter() =
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
            fs = fs,
            cursors = cursors,
            backupDir = backupDir,
        )

    /** The importer — never called on this path, but the repository wants one. */
    private fun importer() =
        BackupImporter(
            log = Logger.withTag("BackupViewModelCreateToTest"),
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
            agendaViewDao = db.agendaViewDao(),
            attachmentDao = db.attachmentDao(),
            annotationDao = db.annotationDao(),
            attachmentStorage = AttachmentStorage(fs, File(tempDir, "attachments").absolutePath),
            codec = JvmBackupCodec(),
            clock = Clock.System,
            createFileSource = JvmFileSourceFactory(),
            tasksSettings = FakeTasksSettingsRepository(),
        )

    /** Writes a task into the database. */
    private suspend fun seedTask(
        id: String,
        title: String,
    ) {
        db.taskDao().upsert(task(id, title))
    }

    /** Changes the title of a task — a row that changed. */
    private suspend fun renameTask(
        id: String,
        title: String,
    ) {
        db.taskDao().upsert(task(id, title))
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

    /** The change files in a directory. */
    private fun deltaFilesIn(dir: String): List<String> =
        File(dir).listFiles()?.filter { it.name.endsWith(".delta") }?.map { it.name } ?: emptyList()

    private val stubSettingsExporter =
        object : SettingsExporter(emptySet()) {
            override suspend fun exportAsJson(): String = """{"schemaVersion":1}"""
        }

    private val stubSettingsImporter =
        object : SettingsImporter(emptySet()) {
            override suspend fun importFromJson(json: String): SettingsImporter.ImportResult =
                SettingsImporter.ImportResult.Success
        }

    /**
     * The cloud is not part of this test.
     *
     * The repository wants a remote service, and the service wants a bucket. A
     * bucket that refuses every call says "this test never touches the cloud"
     * louder than a bucket that pretends to be one.
     */
    private class UnusedBucket : BackupBucket {
        override suspend fun upload(
            path: String,
            source: ByteSource,
            sizeBytes: Long,
        ) = error("this test never touches the cloud")

        override suspend fun download(
            path: String,
            sink: ByteSink,
        ) = error("this test never touches the cloud")

        override suspend fun list(prefix: String): List<BucketObject> = error("this test never touches the cloud")
    }
}
