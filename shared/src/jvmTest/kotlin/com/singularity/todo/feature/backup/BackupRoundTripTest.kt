@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.backup

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.backup.BackupExporter
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.backup.exportOptions
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.database.textIndex
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
import com.singularity.todo.test.fakes.FakeUnitOfWork
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private val tempDirLazy = lazy {
        Files.createTempDirectory("singularity-backup-round-trip-").toFile()
    }
    private val tempDir: File by tempDirLazy

    private val sourceDbLazy = lazy { buildDb("source.db") }
    private val sourceDb: AppDatabase by sourceDbLazy

    private val targetDbLazy = lazy { buildDb("target.db") }
    private val targetDb: AppDatabase by targetDbLazy

    @AfterTest
    fun tearDown() {
        if (sourceDbLazy.isInitialized()) sourceDb.close()
        if (targetDbLazy.isInitialized()) targetDb.close()
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    private fun buildDb(name: String): AppDatabase = AppDatabaseFactory.build(
        driver = createSqlDriver(),
        dbPath = File(tempDir, name).absolutePath,
    )

    @Test
    fun `a task survives export and restore`() = runTest {
        val owner = UserId("backup-user")
        seedTask(owner)

        val fs = JvmFileSystem()
        val destPath = File(tempDir, "round-trip.zip").absolutePath

        val exported = exporter(fs).export(
            exportOptions {
                this.userId = owner
                this.destPath = destPath
                includeAttachments = true
            },
        )
        assertTrue(exported.isSuccess, "export failed: ${exported.exceptionOrNull()}")
        assertTrue(File(destPath).isFile, "the backup file was not written")

        val restored = importer(fs).import(
            importOptions {
                sourcePath = destPath
                targetUserId = owner
                overwriteExisting = true
            },
        )
        assertTrue(restored.isSuccess, "import failed: ${restored.exceptionOrNull()}")

        val restoredTasks = targetDb.taskDao().listAllForUser(owner.value)
        assertEquals(1, restoredTasks.size, "the restored database holds ${restoredTasks.size} tasks")
        assertEquals("Backup round trip", restoredTasks.first().title)
        assertEquals(
            "InProgress",
            restoredTasks.first().workflowState,
            "the state the user claimed for the task's work must survive the round trip",
        )
    }

    /** Writes the task the round-trip starts from into the source database. */
    private suspend fun seedTask(owner: UserId) {
        val auth = FakeAuthRepository(Session.Anonymous(owner))
        val currentUser = FakeProfileAwareCurrentUser(auth)
        val repository = TaskRepositoryImpl(
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
    fun `a dependency edge keeps its verb through the round trip`() = runTest {
        val owner = UserId("backup-user")
        seedGraph(owner)

        val fs = JvmFileSystem()
        val destPath = File(tempDir, "verbs.zip").absolutePath

        val exported = exporter(fs).export(
            exportOptions {
                this.userId = owner
                this.destPath = destPath
                includeAttachments = false
            },
        )
        assertTrue(exported.isSuccess, "export failed: ${exported.exceptionOrNull()}")

        val restored = importer(fs).import(
            importOptions {
                sourcePath = destPath
                targetUserId = owner
                overwriteExisting = true
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
        val repository = TaskRepositoryImpl(
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
    private fun exporter(fs: JvmFileSystem) = BackupExporter(
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
    )

    /** The importer writes into the empty target database. */
    private fun importer(fs: JvmFileSystem) = BackupImporter(
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
    )
}
