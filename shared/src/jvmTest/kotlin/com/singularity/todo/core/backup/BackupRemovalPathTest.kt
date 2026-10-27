@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.backup

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The removal path, one kind at a time: every kind takes its own row out of its own
 * table.
 *
 * ## What the arch test cannot say
 *
 * `BackupRemovalPathTest` (the arch test) says every kind has a branch in the
 * importer's `when (kind)`. It says nothing about *what* the branch does: a branch that
 * names the wrong DAO, or the right DAO with the wrong key, or the right statement in
 * the wrong order, is a branch that exists.
 *
 * This test is the other half. It writes one row of every kind and then takes the
 * kinds out **one at a time**, in the order the kinds are declared, and after each
 * one it looks at every table.
 *
 * One at a time is what makes the difference visible. A restore that removed every
 * kind at once could delete the wrong table and still leave every table empty: the
 * row it deleted by mistake was going to be deleted anyway. With one kind per
 * restore, the rows of the kinds that have not been removed yet are still there —
 * so a branch that reaches into another table empties a table it should not have
 * touched, and the test sees it.
 *
 * ## The order is in this test too
 *
 * The kinds are removed in the order they are declared — the children before the
 * parents — because the link tables carry no user id of their own: the statement
 * that removes a link reaches its row through the parent that owns it, and
 * `removeDependencyForUser` deletes only where the task still exists. A restore
 * that took the parents out first would leave the links behind, and this test would
 * see it: the links are still there.
 *
 * ## The keys are the index's keys
 *
 * The removals are named with the keys `BackupRows.index` gives the rows, so the test
 * cannot pass by inventing a key format the restore does not use.
 */
@Tag("slow")
@Tag("slow:data")
class BackupRemovalPathTest {

    private val owner = UserId("removal-owner")

    private val tempDirLazy = lazy {
        Files.createTempDirectory("singularity-backup-removal-").toFile()
    }
    private val tempDir: File by tempDirLazy

    private val dbLazy = lazy {
        AppDatabaseFactory.build(
            driver = createSqlDriver(),
            dbPath = File(tempDir, "target.db").absolutePath,
        )
    }
    private val db: AppDatabase by dbLazy

    private val fs = JvmFileSystem()
    private val settings = FakeTasksSettingsRepository()

    @AfterTest
    fun tearDown() {
        if (dbLazy.isInitialized()) db.close()
        if (tempDirLazy.isInitialized()) tempDir.deleteRecursively()
    }

    @Test
    fun `every kind takes its own row out of its own table`() = runTest {
        val seed = removalSeedPayload(Clock.System.now().toEpochMilliseconds(), owner)

        // The rows travel to the database first: a removal is the absence of a row,
        // so there has to be a row for it to be the absence of.
        restore(writeArchive(seed, "rows.zip"))
        assertEquals(
            emptyMap(),
            survivors().filterValues { it == 0 },
            "these kinds hold no row after the seed — the fixture does not cover every kind, " +
                "and the removals below would prove nothing about them",
        )

        // The keys the index gives the rows, and the rows themselves.
        val index = BackupRows.index(seed)
        assertEquals(
            BackupEntityKind.entries.toSet(),
            index.keys,
            "the fixture must hold a row of every kind, or a kind is not covered",
        )
        val seeded = survivors()
        assertEquals(
            emptyMap(),
            seeded.filterValues { it == 0 },
            "these kinds hold no row after the seed — the fixture does not cover every kind, " +
                "and the removals below would prove nothing about them",
        )

        // One kind per restore, in the order the kinds are declared.
        val removed = mutableSetOf<BackupEntityKind>()
        for (kind in BackupEntityKind.entries) {
            val keys = index.getValue(kind).keys.toList()
            assertTrue(
                keys.isNotEmpty(),
                "the fixture holds no row of $kind — the removal below would remove nothing, " +
                    "and the test would pass without ever removing a $kind",
            )
            restore(
                writeArchive(
                    BackupPayload(
                        schemaVersion = BackupFormat.SCHEMA_VERSION,
                        removals = mapOf(kind to keys),
                    ),
                    "removal-${kind.name}.zip",
                ),
            )
            removed += kind

            // Every kind removed so far is empty, and every kind that is not is
            // untouched. A branch that reaches into another table empties a table
            // that should still be full.
            assertEquals(
                seeded.filterKeys { it in removed }.mapValues { 0 },
                survivors().filterKeys { it in removed },
                "the restore of $kind left rows behind — the branch for $kind does not " +
                    "delete what it says it deletes",
            )
            assertEquals(
                seeded.filterKeys { it !in removed },
                survivors().filterKeys { it !in removed },
                "the restore of $kind changed the rows of a kind it was not told to touch — " +
                    "the branch for $kind reaches into another table",
            )
        }
    }

    // ── the fixture ───────────────────────────────────────────────────────────

    // ── the archive ───────────────────────────────────────────────────────────

    /** The archive that carries [payload], written at [name], and its path. */
    private suspend fun writeArchive(payload: BackupPayload, name: String): String {
        val payloadBytes = StableJson
            .encodeToString(BackupPayload.serializer(), payload)
            .encodeToByteArray()
        val manifest = BackupDomain.buildManifest(
            appVersion = "test",
            nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
            userId = owner,
            payloadBytes = payloadBytes,
            counts = BackupDomain.countsOf(payload),
        )
        val manifestBytes = StableJson
            .encodeToString(BackupManifest.serializer(), manifest)
            .encodeToByteArray()
        val path = File(tempDir, name).absolutePath
        val written = JvmBackupCodec().export(manifestBytes, payloadBytes, emptyList(), path, fs)
        assertTrue(written.isSuccess, "the archive was not written: ${written.exceptionOrNull()}")
        return path
    }

    /** The restore under test. */
    private fun importer() = BackupImporter(
        log = Logger(loggerConfigInit(CommonWriter()), "backup-removal-path"),
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
        tasksSettings = settings,
    )

    /** Restores the archive at [path] into the database under test. */
    private suspend fun restore(path: String) {
        val restored = importer().import(
            importOptions {
                sourcePath = path
                targetUserId = owner
                overwriteExisting = true
            },
        )
        assertTrue(restored.isSuccess, "the restore failed: ${restored.exceptionOrNull()}")
    }

    // ── what is left ──────────────────────────────────────────────────────────

    /** How many rows of each kind the database still holds. */
    private suspend fun survivors(): Map<BackupEntityKind, Int> = mapOf(
        BackupEntityKind.TASKS to db.taskDao().listAllForUser(owner.value).size,
        BackupEntityKind.NOTES to db.noteDao().listAllForUser(owner.value).size,
        BackupEntityKind.PROJECTS to db.projectDao().listAllForUser(owner.value).size,
        BackupEntityKind.TAGS to db.tagDao().listAllForUser(owner.value).size,
        BackupEntityKind.TAG_GROUPS to db.tagGroupDao().listAllForUser(owner.value).size,
        BackupEntityKind.PROJECT_TAG_GROUPS to db.projectInheritedTagGroupDao()
            .listAllForUser(owner.value).size,
        BackupEntityKind.ATTACHMENTS to db.attachmentDao().listAllForUser(owner.value).size,
        BackupEntityKind.ATTACHMENT_ANNOTATIONS to db.annotationDao().listAllForUser(owner.value).size,
        BackupEntityKind.TASK_TAGS to db.taskDao().listAllTagsForUser(owner.value).size,
        BackupEntityKind.TASK_DEPENDENCIES to db.taskDao().listAllDependenciesForUser(owner.value).size,
        BackupEntityKind.AGENDA_VIEWS to db.agendaViewDao().listAllForUser(owner.value).size,
        BackupEntityKind.TASK_REMINDERS to db.reminderDao().listAllForUser(owner.value).size,
        BackupEntityKind.PROJECT_REMINDERS to db.projectReminderDao().listAllForUser(owner.value).size,
        BackupEntityKind.CHECKLIST_ITEMS to db.checklistDao().listAllForUser(owner.value).size,
        BackupEntityKind.SAVED_SEARCHES to db.savedSearchDao().listAllForUser(owner.value).size,
        BackupEntityKind.TIME_ENTRIES to db.timeEntryDao().listAllForUser(owner.value).size,
        BackupEntityKind.PROFILES to db.profileDao().listAll().size,
    )
}
