@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.backup

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.loggerConfigInit
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The restore that keeps the rows the account already has.
 *
 * ## What these tests are about
 *
 * `keepExistingRows` is a promise with three parts: the account's rows survive,
 * the archive's new rows land, and the result says which is which. Each part can
 * be broken without the others noticing — a filter that drops the new rows too, a
 * writer that runs anyway, a count that says "kept" for a row that was written.
 *
 * The fixture is the removal test's: one row of every kind, so a kind the filter
 * forgets shows up as a row written over one the account had.
 *
 * ## Why the default is tested here too
 *
 * The change is worth nothing if it moved the default. The first test is the old
 * behaviour, unchanged, and it fails the moment the flag leaks into the ordinary
 * path.
 */
@Tag("slow")
@Tag("slow:data")
class BackupKeepExistingTest {
    private lateinit var fixture: DatabaseTestFixture
    private lateinit var db: AppDatabase

    private val owner = UserId("keep-owner")

    private val fs = JvmFileSystem()
    private val settings = FakeTasksSettingsRepository()
    private val attachmentStorage by lazy {
        AttachmentStorage(
            fs,
            File(fixture.directory(), "attachments").absolutePath,
        )
    }
    private val now = Clock.System.now().toEpochMilliseconds()

    @BeforeEach
    fun setUp() {
        fixture = DatabaseTestFixture.onDisk()
        db = fixture.database()
    }

    @AfterEach
    fun tearDown() {
        fixture.close()
    }

    /** The default restore is the archive winning, exactly as it always was. */
    @Test
    fun `the default restore still lets the archive win`() =
        runTest {
            restore(writeArchive(seed(), "seed.zip"))
            assertEquals("A task", task("task-1").title, "the fixture wrote the archive's own title")

            val restored =
                restore(
                    writeArchive(
                        BackupPayload(
                            schemaVersion = BackupFormat.SCHEMA_VERSION,
                            tasks = listOf(taskRow("task-1", "the archive's older title")),
                        ),
                        "older.zip",
                    ),
                )

            assertEquals(
                "the archive's older title",
                task("task-1").title,
                "an ordinary restore writes the archive's row over the account's",
            )
            assertEquals(EntityCounts(), restored.keptEntityCounts, "an ordinary restore keeps nothing")
        }

    /** A kept row keeps the account's version; a row the account does not have is written. */
    @Test
    fun `a restore that keeps existing rows writes only the rows the account does not have`() =
        runTest {
            restore(writeArchive(seed(), "seed.zip"))

            val restored =
                restore(
                    writeArchive(
                        BackupPayload(
                            schemaVersion = BackupFormat.SCHEMA_VERSION,
                            tasks =
                                listOf(
                                    taskRow("task-1", "the archive's older title"),
                                    taskRow("task-3", "only in the archive"),
                                ),
                        ),
                        "older.zip",
                    ),
                    keepExisting = true,
                )

            assertEquals("A task", task("task-1").title, "a row the account has keeps the account's version")
            assertEquals("only in the archive", task("task-3").title, "a row the account does not have is written")
            assertEquals(1, restored.keptEntityCounts.tasks, "the row that was there is reported as kept")
            assertEquals(2, restored.entityCounts.tasks, "the archive still says what it carried")
        }

    /**
     * A change that says a row was removed does not take it out of an account that keeps it.
     *
     * "Keep what is there" and "take this row away" are the same row asked twice,
     * and the answer is the one the person chose: an additive restore removes
     * nothing.
     */
    @Test
    fun `a row the archive says was removed stays when the rows there are kept`() =
        runTest {
            restore(writeArchive(seed(), "seed.zip"))

            val restored =
                restore(
                    writeArchive(
                        BackupPayload(
                            schemaVersion = BackupFormat.SCHEMA_VERSION,
                            removals = mapOf(BackupEntityKind.TASKS to listOf("task-1", "task-2")),
                        ),
                        "removal.zip",
                    ),
                    keepExisting = true,
                )

            assertEquals(
                listOf("task-1", "task-2"),
                db
                    .taskDao()
                    .listAllForUser(owner.value)
                    .map { it.id }
                    .sorted(),
                "a restore that keeps the rows there removes nothing",
            )
            assertEquals(0, restored.entityCounts.tasks, "the archive carries no task row")
            assertEquals(EntityCounts(), restored.keptEntityCounts, "a removal is not a row, so it keeps nothing")
        }

    /** Restoring an archive the account already holds reports every row kept, and changes nothing. */
    @Test
    fun `a restore into an account that already holds the archive reports every row kept`() =
        runTest {
            val payload = seed()
            restore(writeArchive(payload, "seed.zip"))
            val before = survivors()

            val restored = restore(writeArchive(payload, "again.zip"), keepExisting = true)

            assertEquals(
                BackupDomain.countsOf(payload),
                restored.keptEntityCounts,
                "every row of the archive is already there, and every kind says so",
            )
            assertEquals(BackupDomain.countsOf(payload), restored.entityCounts, "the archive is the same archive")
            assertEquals(before, survivors(), "nothing was written and nothing was removed")
        }

    // ── the fixture ───────────────────────────────────────────────────────────

    /** One row of every kind, owned by the account under test. */
    private fun seed(): BackupPayload = removalSeedPayload(now, owner)

    /** A task row of the archive, as an older export would carry it. */
    private fun taskRow(
        id: String,
        title: String,
    ) = TaskDto(id = id, title = title, createdAt = now, updatedAt = now)

    /** The task, as the account holds it. */
    private suspend fun task(id: String) = db.taskDao().listAllForUser(owner.value).single { it.id == id }

    // ── the archive ───────────────────────────────────────────────────────────

    /** The archive that carries [payload], written at [name], and its path. */
    private suspend fun writeArchive(
        payload: BackupPayload,
        name: String,
        attachments: List<Pair<String, String>> = emptyList(),
    ): String {
        val payloadBytes =
            StableJson
                .encodeToString(BackupPayload.serializer(), payload)
                .encodeToByteArray()
        val manifest =
            BackupDomain.buildManifest(
                appVersion = "test",
                nowEpochMillis = now,
                userId = owner,
                payloadBytes = payloadBytes,
                counts = BackupDomain.countsOf(payload),
            )
        val manifestBytes =
            StableJson
                .encodeToString(BackupManifest.serializer(), manifest)
                .encodeToByteArray()
        val path = File(fixture.directory(), name).absolutePath
        val written = JvmBackupCodec().export(manifestBytes, payloadBytes, attachments, path, fs)
        assertTrue(written.isSuccess, "the archive was not written: ${written.exceptionOrNull()}")
        return path
    }

    // ── the restore ───────────────────────────────────────────────────────────

    /** The restore under test. */
    private fun importer() =
        BackupImporter(
            log = Logger(loggerConfigInit(CommonWriter()), "backup-keep-existing"),
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
            attachmentStorage = attachmentStorage,
            codec = JvmBackupCodec(),
            clock = Clock.System,
            createFileSource = JvmFileSourceFactory(),
            tasksSettings = settings,
        )

    /** Restores the archive at [path], and answers what it did. */
    private suspend fun restore(
        path: String,
        keepExisting: Boolean = false,
    ): RestoreResult {
        val restored =
            importer().import(
                importOptions {
                    sourcePath = path
                    targetUserId = owner
                    keepExistingRows = keepExisting
                },
            )
        assertTrue(restored.isSuccess, "the restore failed: ${restored.exceptionOrNull()}")
        return restored.getOrThrow()
    }

    // ── what the account holds ────────────────────────────────────────────────

    /** How many rows of each kind the account holds. */
    private suspend fun survivors(): Map<BackupEntityKind, Int> =
        mapOf(
            BackupEntityKind.TASKS to db.taskDao().listAllForUser(owner.value).size,
            BackupEntityKind.NOTES to db.noteDao().listAllForUser(owner.value).size,
            BackupEntityKind.PROJECTS to db.projectDao().listAllForUser(owner.value).size,
            BackupEntityKind.TAGS to db.tagDao().listAllForUser(owner.value).size,
            BackupEntityKind.TAG_GROUPS to db.tagGroupDao().listAllForUser(owner.value).size,
            BackupEntityKind.PROJECT_TAG_GROUPS to
                db
                    .projectInheritedTagGroupDao()
                    .listAllForUser(owner.value)
                    .size,
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

    /**
     * A kept attachment row keeps the account's bytes: the archive's entry for the
     * same id is not written over the file the account already has.
     */
    @Test
    fun `keep-existing leaves attachment bytes alone when the archive carries different bytes`() =
        runTest {
            restore(writeArchive(seed(), "seed.zip"))
            val accountBytes = "account-bytes".toByteArray()
            val accountPath = attachmentStorage.saveBytes("task-1", "attachment-1", accountBytes, "txt").getOrThrow()
            db.attachmentDao().upsert(
                com.singularity.todo.core.attachments.AttachmentEntity(
                    id = "attachment-1",
                    taskId = "task-1",
                    userId = owner.value,
                    type = "file",
                    title = "a.txt",
                    localPath = accountPath,
                    fileSizeBytes = accountBytes.size.toLong(),
                    mimeType = "text/plain",
                    checksum = BackupDomain.sha256Hex(accountBytes),
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            val archiveBytes = "archive-different-bytes".toByteArray()
            val archiveFile = File(fixture.directory(), "att-archive.dat")
            archiveFile.writeBytes(archiveBytes)
            val archivePayload =
                BackupPayload(
                    schemaVersion = BackupFormat.SCHEMA_VERSION,
                    tasks = removalTaskRows(now),
                    attachments =
                        listOf(
                            AttachmentDto(
                                id = "attachment-1",
                                taskId = "task-1",
                                type = "file",
                                title = "a.txt",
                                fileSizeBytes = archiveBytes.size.toLong(),
                                mimeType = "text/plain",
                                checksum = BackupDomain.sha256Hex(archiveBytes),
                                createdAt = now,
                                updatedAt = now,
                            ),
                        ),
                )

            val restored =
                restore(
                    writeArchive(
                        archivePayload,
                        "att.zip",
                        attachments = listOf("attachment-1.dat" to archiveFile.absolutePath),
                    ),
                    keepExisting = true,
                )

            assertEquals(1, restored.keptEntityCounts.attachments, "the attachment row was already there")
            val row = db.attachmentDao().listAllForUser(owner.value).single { it.id == "attachment-1" }
            assertEquals(accountPath, row.localPath, "kept row keeps the account's local path")
            assertTrue(File(accountPath).readBytes().contentEquals(accountBytes), "kept row keeps the account's bytes")
        }
}
