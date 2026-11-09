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
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Two tags of one name, and the unique index that does not allow them.
 *
 * `tags` carries a unique `(user_id, name_normalized)`; the legacy app did not.
 * The restore keeps both rows — the second one under a numbered name — because
 * dropping it would take the tasks tagged with it along.
 */
@Tag("slow")
@Tag("slow:data")
class BackupTagNameCollisionTest {
    private val owner = UserId("tag-name-owner")

    // The fixture owns the database and the directory that holds it, so the
    // archives this test writes are removed with it.
    private val fixture = DatabaseTestFixture.onDisk()
    private val tempDir: File by lazy { fixture.directory() }
    private val db: AppDatabase by lazy { fixture.database("target") }

    private val fs = JvmFileSystem()
    private val now = Clock.System.now().toEpochMilliseconds()

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `a tag whose name the account already has keeps its row under a numbered name`() =
        runTest {
            restore(archiveOf(tag("tag-1", "tools")), "first.zip")
            restore(archiveOf(tag("tag-2", "Tools")), "second.zip")

            assertEquals(
                listOf("tools", "Tools (2)"),
                names(),
                "the second tag keeps its row and its links, and takes a free name",
            )
            assertEquals(
                setOf("tag-1", "tag-2"),
                db
                    .tagDao()
                    .listAllForUser(owner.value)
                    .map { it.id }
                    .toSet(),
                "the renamed tag is the archive's own row, not a replacement",
            )
            restore(archiveOf(tag("tag-2", "Tools")), "second-again.zip")
            assertEquals(
                listOf("tools", "Tools (2)"),
                names(),
                "restoring the same archive again does not add another suffix",
            )
        }

    @Test
    fun `two tags of one name inside one archive both land`() =
        runTest {
            restore(archiveOf(tag("tag-1", "tools"), tag("tag-2", "tools")), "both.zip")

            assertEquals(listOf("tools", "tools (2)"), names(), "one of the two is renamed, neither is lost")
        }

    private fun tag(
        id: String,
        name: String,
    ) = TagDto(id = id, name = name, color = 0, createdAt = now, updatedAt = now)

    private suspend fun names() =
        db
            .tagDao()
            .listAllForUser(owner.value)
            .map { it.name }
            .sortedBy { it.lowercase() }

    private fun archiveOf(vararg tags: TagDto) =
        BackupPayload(schemaVersion = BackupFormat.SCHEMA_VERSION, tags = tags.toList())

    private suspend fun restore(
        payload: BackupPayload,
        name: String,
    ) {
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
        val path = File(tempDir, name).absolutePath
        JvmBackupCodec().export(manifestBytes, payloadBytes, emptyList(), path, fs).getOrThrow()

        val restored =
            importer().import(
                importOptions {
                    sourcePath = path
                    targetUserId = owner
                },
            )
        assertTrue(restored.isSuccess, "the restore failed: ${restored.exceptionOrNull()}")
    }

    private fun importer() =
        BackupImporter(
            log = Logger(loggerConfigInit(CommonWriter()), "backup-tag-name"),
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
}
