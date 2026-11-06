@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.backup

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.backup.BackupCursorStore
import com.singularity.todo.core.backup.BackupExporter
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.backup.TaskDto
import com.singularity.todo.core.backup.exportOptions
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.backup.toEntity
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.FakeSyncRepository
import com.singularity.todo.feature.tasks.data.TaskRepositoryImpl
import com.singularity.todo.feature.tasks.domain.logic.DependencyValidatorImpl
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The backup round-trip: what a task was, is what comes back.
 *
 * The Maestro round-trip flow could not reach the Backup tab, so it never ran the
 * round-trip itself — the failure said nothing about whether restoring works. This
 * test runs it: a task is written, exported to a real file, and imported into a
 * second, empty database. The second database is a different file rather than a
 * wiped one, because "the data was restored" and "the data was never removed" are
 * different claims and only the first one is worth making.
 *
 * The flow's own expectation — a task that was there before the backup comes back
 * after the restore — is the last assertion here.
 */
@Tag("slow")
@Tag("slow:data")
class BackupRoundTripTest {
    private val tempDirLazy =
        lazy {
            Files.createTempDirectory("singularity-backup-round-trip-").toFile()
        }
    private val tempDir: File by tempDirLazy

    private val sourceDbLazy = lazy { buildDb("source.db") }
    private val sourceDb: AppDatabase by sourceDbLazy

    private val targetDbLazy = lazy { buildDb("target.db") }
    private val targetDb: AppDatabase by targetDbLazy

    /** Shared by the importer under test, so the marker it clears can be read back. */
    private val repairSettings = FakeTasksSettingsRepository()

    @AfterTest
    fun tearDown() {
        if (sourceDbLazy.isInitialized()) sourceDb.close()
        if (targetDbLazy.isInitialized()) targetDb.close()
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    private fun buildDb(name: String): AppDatabase =
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = File(tempDir, name).absolutePath,
        )

    @Test
    fun `a task survives export and restore`() =
        runTest {
            val owner = UserId("backup-user")
            seedTask(owner)

            val fs = JvmFileSystem()
            val destPath = File(tempDir, "round-trip.zip").absolutePath

            val exported =
                exporter(fs).export(
                    exportOptions {
                        this.userId = owner
                        this.destPath = destPath
                        includeAttachments = true
                    },
                )
            assertTrue(exported.isSuccess, "export failed: ${exported.exceptionOrNull()}")
            assertTrue(File(destPath).isFile, "the backup file was not written")

            // A profile that was already repaired must be visited again after a
            // restore: the archive may carry rows from before the series model.
            repairSettings.setRecurringSeriesRepairVersion(
                owner.value,
                com.singularity.todo.feature.tasks.domain.usecase.RepairRecurringSeriesUseCase.VERSION,
            )

            val restored =
                importer(fs).import(
                    importOptions {
                        sourcePath = destPath
                        targetUserId = owner
                    },
                )
            assertTrue(restored.isSuccess, "import failed: ${restored.exceptionOrNull()}")
            assertEquals(
                0,
                repairSettings.recurringSeriesRepairVersion(owner.value).first(),
                "a restore must clear the repair marker so the next launch adopts legacy rows",
            )

            val restoredTasks = targetDb.taskDao().listAllForUser(owner.value)
            assertEquals(1, restoredTasks.size, "the restored database holds ${restoredTasks.size} tasks")
            assertEquals("Backup round trip", restoredTasks.first().title)
            assertEquals(
                "InProgress",
                restoredTasks.first().workflowState,
                "the state the user claimed for the task's work must survive the round trip",
            )
        }

    /**
     * A sub-task comes back under the same parent it was exported with.
     *
     * The parent link is a column and a domain field, but the payload used to
     * have nowhere to put it: a restored account came back flat — every task
     * was there, the hierarchy was not.
     */
    @Test
    fun `a sub-task comes back under the same parent`() =
        runTest {
            val owner = UserId("backup-user")
            seedSubtask(owner)

            val fs = JvmFileSystem()
            val destPath = File(tempDir, "sub-task.zip").absolutePath

            val exported =
                exporter(fs).export(
                    exportOptions {
                        this.userId = owner
                        this.destPath = destPath
                        includeAttachments = false
                    },
                )
            assertTrue(exported.isSuccess, "export failed: ${exported.exceptionOrNull()}")

            val restored =
                importer(fs).import(
                    importOptions {
                        sourcePath = destPath
                        targetUserId = owner
                    },
                )
            assertTrue(restored.isSuccess, "import failed: ${restored.exceptionOrNull()}")

            val parents =
                targetDb.taskDao().listAllForUser(owner.value).associate { it.id to it.parentTaskId }
            assertEquals(
                "round-trip-parent",
                parents["round-trip-child"],
                "a restored sub-task must be a child of the same parent it had before the export",
            )
        }

    /**
     * A sub-task whose parent is not in the archive comes back top-level.
     *
     * A row can name a parent that no longer exists — the parent was deleted, or
     * it never made it into the archive. The restore must not fail over it, and
     * must not leave the task pointing at a row nothing writes: a dangling id
     * would hide the task from every list that asks for top-level tasks.
     */
    @Test
    fun `a sub-task whose parent is not in the archive comes back as a top-level task`() =
        runTest {
            val owner = UserId("backup-user")
            val now = Clock.System.now().toEpochMilliseconds()
            sourceDb.taskDao().upsert(
                TaskDto(
                    id = "orphan-child",
                    title = "Orphan sub-task",
                    createdAt = now,
                    updatedAt = now,
                ).toEntity(owner.value).copy(parentTaskId = "parent-that-is-gone"),
            )

            val fs = JvmFileSystem()
            val destPath = File(tempDir, "orphan.zip").absolutePath

            val exported =
                exporter(fs).export(
                    exportOptions {
                        this.userId = owner
                        this.destPath = destPath
                        includeAttachments = false
                    },
                )
            assertTrue(exported.isSuccess, "export failed: ${exported.exceptionOrNull()}")

            val restored =
                importer(fs).import(
                    importOptions {
                        sourcePath = destPath
                        targetUserId = owner
                    },
                )
            assertTrue(
                restored.isSuccess,
                "the restore failed over a parent the archive does not carry: ${restored.exceptionOrNull()}",
            )

            val orphan = targetDb.taskDao().listAllForUser(owner.value).single { it.id == "orphan-child" }
            assertNull(
                orphan.parentTaskId,
                "a parent the restore does not write leaves the task top-level, not pointing at a row nothing writes",
            )
        }

    /** Writes a parent and the sub-task that hangs off it into the source database. */
    private suspend fun seedSubtask(owner: UserId) {
        val auth = FakeAuthRepository(Session.Anonymous(owner))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        val repository =
            TaskRepositoryImpl(
                taskDao = sourceDb.taskDao(),
                textIndex = sourceDb.textIndex(currentUser),
                clock = Clock.System,
                currentUser = currentUser,
                syncRepository = FakeSyncRepository(),
                dependencyValidator = DependencyValidatorImpl(sourceDb.taskDao(), currentUser),
                unitOfWork = FakeUnitOfWork(),
            )
        val now = Clock.System.now()
        repository.create(
            Task(
                id = TaskId("round-trip-parent"),
                title = "The parent",
                userId = owner,
                createdAt = now,
                updatedAt = now,
            ),
        )
        repository.create(
            Task(
                id = TaskId("round-trip-child"),
                title = "The sub-task",
                userId = owner,
                createdAt = now,
                updatedAt = now,
                parentTaskId = TaskId("round-trip-parent"),
            ),
        )
    }

    /**
     * Exporting never reads the archive — or an attachment — back into memory.
     *
     * The archive is written in pieces through `openWrite`, and its size is asked
     * of the filesystem rather than measured by reading the file back. This test
     * is the anchor for that: the filesystem it hands the exporter refuses every
     * whole-file read, so an exporter that reaches for `readBytes` — for the size
     * of the archive, or for an attachment's bytes — fails here and does not fail
     * on a phone, where the same code simply costs the whole account in memory.
     */
    @Test
    fun `exporting never reads a whole file into memory`() =
        runTest {
            val owner = UserId("backup-user")
            seedTask(owner)

            val fs = NoWholeFileReads(JvmFileSystem())
            val destPath = File(tempDir, "no-whole-reads.zip").absolutePath

            val exported =
                exporter(fs).export(
                    exportOptions {
                        this.userId = owner
                        this.destPath = destPath
                        includeAttachments = true
                    },
                )

            assertTrue(
                exported.isSuccess,
                "the export read a whole file into memory: ${exported.exceptionOrNull()}",
            )
            assertTrue(File(destPath).isFile, "the backup file was not written")
        }

    /** Writes the task the round-trip starts from into the source database. */
    private suspend fun seedTask(owner: UserId) {
        val auth = FakeAuthRepository(Session.Anonymous(owner))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        val repository =
            TaskRepositoryImpl(
                taskDao = sourceDb.taskDao(),
                textIndex = sourceDb.textIndex(currentUser),
                clock = Clock.System,
                currentUser = currentUser,
                syncRepository = FakeSyncRepository(),
                dependencyValidator = DependencyValidatorImpl(sourceDb.taskDao(), currentUser),
                unitOfWork = FakeUnitOfWork(),
            )
        repository.create(
            Task(
                id = TaskId("round-trip-task"),
                title = "Backup round trip",
                userId = owner,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                // A claimed state, so the round trip has something to carry: a
                // task that was never claimed would restore as `Open` whether
                // the field travelled or not.
                workflowState = TaskWorkflowState.InProgress,
            ),
        )
    }

    @Test
    fun `a dependency edge keeps its verb through the round trip`() =
        runTest {
            val owner = UserId("backup-user")
            seedGraph(owner)

            val fs = JvmFileSystem()
            val destPath = File(tempDir, "verbs.zip").absolutePath

            val exported =
                exporter(fs).export(
                    exportOptions {
                        this.userId = owner
                        this.destPath = destPath
                        includeAttachments = false
                    },
                )
            assertTrue(exported.isSuccess, "export failed: ${exported.exceptionOrNull()}")

            val restored =
                importer(fs).import(
                    importOptions {
                        sourcePath = destPath
                        targetUserId = owner
                    },
                )
            assertTrue(restored.isSuccess, "import failed: ${restored.exceptionOrNull()}")

            val edges = targetDb.taskDao().listAllDependenciesForUser(owner.value)
            val verbs = edges.associate { it.taskId to it.verb }
            assertEquals(
                mapOf("blocks" to "BLOCKS", "follows" to "FOLLOWS_UP"),
                verbs,
                "a relation that only follows up must not come back as a block",
            )
        }

    /**
     * Writes a small graph with two kinds of edge into the source database.
     *
     * One edge blocks, the other only follows up. The two mean different
     * things: the first makes the task wait, the second does not. A backup that
     * kept the ends of the edge and dropped the meaning turned the second into
     * the first — and the close rule reads exactly these edges.
     */
    private suspend fun seedGraph(owner: UserId) {
        val auth = FakeAuthRepository(Session.Anonymous(owner))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        val repository =
            TaskRepositoryImpl(
                taskDao = sourceDb.taskDao(),
                textIndex = sourceDb.textIndex(currentUser),
                clock = Clock.System,
                currentUser = currentUser,
                syncRepository = FakeSyncRepository(),
                dependencyValidator = DependencyValidatorImpl(sourceDb.taskDao(), currentUser),
                unitOfWork = FakeUnitOfWork(),
            )
        val now = Clock.System.now()
        listOf("dep", "blocks", "follows").forEach { id ->
            repository.create(
                Task(
                    id = TaskId(id),
                    title = "Task $id",
                    userId = owner,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        repository.setDependency(TaskId("blocks"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)
        repository.setDependency(TaskId("follows"), TaskId("dep"), DependencyVerb.FOLLOWS_UP, enabled = true)
    }

    /** The exporter reads the source database. */
    private fun exporter(fs: FileSystem) =
        BackupExporter(
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

    /** The importer writes into the empty target database. */
    private fun importer(fs: JvmFileSystem) =
        BackupImporter(
            log = Logger(loggerConfigInit(CommonWriter()), "backup-round-trip"),
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
            tasksSettings = repairSettings,
        )
}

/**
 * A filesystem that refuses every whole-file read, and delegates everything else.
 *
 * The archive is meant to travel in pieces. This double makes that a property a
 * test can check: the streaming paths (`openRead`, `openWrite`, `stat`) work as
 * they normally would, and `readBytes` — the one call that loads a whole file into
 * memory — fails loudly instead of quietly costing the account's largest files.
 */
private class NoWholeFileReads(private val delegate: FileSystem) : FileSystem by delegate {
    override suspend fun readBytes(path: String): ByteArray = error("a whole file was read into memory: $path")
}
