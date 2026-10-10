package com.singularity.todo.core.backup

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.annotation.AnchorResolution
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationEntity
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationRepositoryImpl
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Annotations survive a backup round trip — the failure mode that is silent by nature.
 *
 * A restore that dropped the notes would import cleanly: the attachment comes back, the
 * files are on disk, and every note is gone with nothing in the log saying so. Asserting
 * that the DTO round-trips in isolation would not catch it, because the DTO was never the
 * gap — the exporter, the payload, the manifest counts and the importer were, one layer at
 * a time and each looking complete.
 *
 * `slow`: the class crosses a process boundary on both sides — a real SQLite file through
 * Room's generated DAOs, and a real zip through `java.util.zip`. Neither is slow; both are
 * why the class is tagged rather than `fast`.
 */
@Tag("slow")
class BackupAnnotationRoundTripTest {

    private lateinit var tempDir: File
    private lateinit var sourceDb: AppDatabase
    private lateinit var targetDb: AppDatabase

    private val user = UserId.fromString("user-round-trip")
    private val fs = JvmFileSystem()
    private val codec = JvmBackupCodec()
    private val fileSourceFactory = JvmFileSourceFactory()

    /**
     * Injected rather than `Clock.System`.
     *
     * Nothing here asserts a timestamp — the row supplies its own — so the only property
     * needed is one that does not read the wall clock: a value the detekt rule
     * `NoDirectClockSystem` exists to enforce, and a real reading would make the test
     * depend on when it ran.
     */
    private val clock = MutableClock()

    @BeforeEach
    fun setUp() {
        tempDir = createTempDirectory("backup-annotation-").toFile()
        sourceDb = open("source.db")
        targetDb = open("target.db")
    }

    @AfterEach
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
        tempDir.deleteRecursively()
    }

    private fun open(name: String): AppDatabase =
        AppDatabaseFactory.build(createSqlDriver(), File(tempDir, name).absolutePath)

    private fun zipPath(name: String): String = File(tempDir, name).absolutePath

    private fun exporter(db: AppDatabase) = BackupExporter(
        taskDao = db.taskDao(),
        noteDao = db.noteDao(),
        projectDao = db.projectDao(),
        tagDao = db.tagDao(),
        attachmentDao = db.attachmentDao(),
        annotationDao = db.annotationDao(),
        agendaViewDao = db.agendaViewDao(),
        reminderDao = db.reminderDao(),
        projectReminderDao = db.projectReminderDao(),
        checklistDao = db.checklistDao(),
        tagGroupDao = db.tagGroupDao(),
        projectTagGroupDao = db.projectInheritedTagGroupDao(),
        savedSearchDao = db.savedSearchDao(),
        timeEntryDao = db.timeEntryDao(),
        codec = codec,
        clock = clock,
        fs = fs,
    )

    private fun importer(db: AppDatabase): BackupImporter {
        val bulkImport = BulkImportPortImpl(
            log = Logger.withTag("BackupAnnotationRoundTripTest"),
            taskDao = db.taskDao(),
            noteDao = db.noteDao(),
            projectDao = db.projectDao(),
            tagDao = db.tagDao(),
            agendaViewDao = db.agendaViewDao(),
            attachmentDao = db.attachmentDao(),
            annotationDao = db.annotationDao(),
            reminderDao = db.reminderDao(),
            projectReminderDao = db.projectReminderDao(),
            checklistDao = db.checklistDao(),
            tagGroupDao = db.tagGroupDao(),
            projectTagGroupDao = db.projectInheritedTagGroupDao(),
            savedSearchDao = db.savedSearchDao(),
            timeEntryDao = db.timeEntryDao(),
            attachmentStorage = AttachmentStorage(fs, File(tempDir, "attachment-files").absolutePath),
            clock = clock,
        )
        return BackupImporter(
            codec = codec,
            createFileSource = fileSourceFactory,
            bulkImportPort = bulkImport,
        )
    }

    /** An attachment whose bytes are not on this disk — the row is what matters here. */
    private fun attachmentRow(id: String, localPath: String? = null) = AttachmentEntity(
        id = id,
        taskId = "task-1",
        userId = user.value,
        type = "Text",
        title = "notes.txt",
        localPath = localPath,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun annotationRow(id: String, attachmentId: String, note: String) = AttachmentAnnotationEntity(
        id = id,
        attachmentId = attachmentId,
        userId = user.value,
        rangeStart = 6,
        rangeEnd = 10,
        quote = "beta",
        note = note,
        createdAt = 2_000L,
        updatedAt = 2_000L,
    )

    @Test
    fun `an annotation written before an export is readable after the import`() = runTest {
        sourceDb.attachmentDao().upsert(attachmentRow("att_1"))
        sourceDb.annotationDao().upsert(annotationRow("ann_1", "att_1", "the second word"))

        val exported = exporter(sourceDb)
            .export(
                exportOptions {
                    userId = user
                    destPath = zipPath("round-trip.zip")
                },
            )
        assertTrue(exported.isSuccess, "export failed: ${exported.exceptionOrNull()}")

        // The manifest is where a dropped entity shows up as a count of zero — and a zero
        // here is the only warning a user would ever get.
        assertEquals(
            1,
            exported.getOrThrow().manifest.entityCounts.attachmentAnnotations,
            "the manifest counts the notes",
        )

        val restored = importer(targetDb)
            .import(
                importOptions {
                    sourcePath = zipPath("round-trip.zip")
                    targetUserId = user
                },
            )
        assertTrue(restored.isSuccess, "import failed: ${restored.exceptionOrNull()}")

        val annotation = assertNotNull(
            targetDb.annotationDao().listAllForUser(user.value).singleOrNull(),
            "the note must come back",
        )
        assertEquals("ann_1", annotation.id)
        assertEquals("att_1", annotation.attachmentId)
        assertEquals(6, annotation.rangeStart)
        assertEquals(10, annotation.rangeEnd)
        assertEquals("beta", annotation.quote)
        assertEquals("the second word", annotation.note)
    }

    @Test
    fun `the restored annotation is reachable through the repository, not just as a row`() = runTest {
        sourceDb.attachmentDao().upsert(attachmentRow("att_1"))
        sourceDb.annotationDao().upsert(annotationRow("ann_1", "att_1", "kept"))
        exporter(sourceDb)
            .export(
                exportOptions {
                    userId = user
                    destPath = zipPath("reachable.zip")
                },
            )
            .getOrThrow()
        importer(targetDb)
            .import(
                importOptions {
                    sourcePath = zipPath("reachable.zip")
                    targetUserId = user
                },
            )
            .getOrThrow()

        // The point of the round trip: the note is not merely a row that survived, it is a
        // note the user can read back through the contract the panel injects.
        val watched = AttachmentAnnotationRepositoryImpl(
            dao = targetDb.annotationDao(),
            clock = clock,
            currentUser = FakeProfileAwareCurrentUser(initialUserId = user),
        ).watchForAttachment(AttachmentId.fromString("att_1")).first()

        assertEquals(listOf("ann_1"), watched.map { it.id.value })
        assertEquals("kept", watched.single().note)
        assertIs<AnchorResolution.Exact>(
            watched.single().anchorIn("alpha beta gamma"),
            "the restored range still resolves against the file it was written for",
        )
    }

    @Test
    fun `the attachment row is restored too, so the restored note has a parent`() = runTest {
        sourceDb.attachmentDao().upsert(attachmentRow("att_1"))
        sourceDb.annotationDao().upsert(annotationRow("ann_1", "att_1", "orphaned without this"))
        exporter(sourceDb)
            .export(
                exportOptions {
                    userId = user
                    destPath = zipPath("parent.zip")
                },
            )
            .getOrThrow()
        importer(targetDb)
            .import(
                importOptions {
                    sourcePath = zipPath("parent.zip")
                    targetUserId = user
                },
            )
            .getOrThrow()

        val restoredAttachment = targetDb.attachmentDao().getById("att_1", user.value)

        assertNotNull(
            restoredAttachment,
            "a restored file with no row is unreachable from the task it belonged to",
        )
        assertEquals(1, targetDb.annotationDao().listAllForUser(user.value).size)
    }

    @Test
    fun `a payload written before annotations existed still decodes`() = runTest {
        // An archive from a client whose payload has no `attachmentAnnotations` key at all.
        // Without a default on the field the whole restore fails on a missing key, which
        // would make adding this table a breaking change for every backup already on disk.
        val payload = StableJson.encodeToString(
            BackupPayload.serializer(),
            BackupPayload(schemaVersion = BackupFormat.SCHEMA_VERSION),
        ).encodeToByteArray()
        val manifest = StableJson.encodeToString(
            BackupManifest.serializer(),
            BackupDomain.buildManifest(
                appVersion = "test",
                nowEpochMillis = 1L,
                userId = user,
                payloadBytes = payload,
                counts = EntityCounts(),
            ),
        ).encodeToByteArray()
        codec.export(manifest, payload, emptyList(), zipPath("legacy.zip"), fs).getOrThrow()

        val result = importer(targetDb)
            .import(
                importOptions {
                    sourcePath = zipPath("legacy.zip")
                    targetUserId = user
                },
            )

        assertTrue(
            result.isSuccess,
            "a pre-annotation backup must still restore: ${result.exceptionOrNull()}",
        )
        assertEquals(0, targetDb.annotationDao().listAllForUser(user.value).size)
    }
}
