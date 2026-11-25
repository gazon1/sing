package com.singularity.todo.feature.tasks.performance

import com.singularity.todo.core.backup.BackupDomain
import com.singularity.todo.core.backup.BackupFormat
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.TagDto
import com.singularity.todo.core.backup.TaskDto
import com.singularity.todo.core.backup.TaskTagDto
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The dataset directory restores through the harness's own path.
 *
 * A dataset directory is not a backup archive: the archive budget does not apply
 * to it, and nothing in the app reads it. The measurement still has to be able to
 * restore one, or a dataset that does not fit an archive cannot be measured at
 * all (#3197).
 *
 * The restore here is the harness's — [restore] — so the path under test is the
 * one the measurements use: `BackupImporter` validates the manifest, migrates
 * the payload and writes the rows. Only the reader differs, and the reader is
 * the only thing this test does not assert: what matters is that the rows land
 * in the database, owned by the profile the measurement reads for.
 */
@Tag("slow")
@Tag("slow:data")
class DatasetDirectoryRestoreTest {
    private lateinit var fixture: DatabaseTestFixture

    @AfterTest
    fun tearDown() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun `a dataset directory restores the rows it holds`() =
        runTest {
            fixture = DatabaseTestFixture.onDisk()
            val db = fixture.database("perf")
            val owner = UserId("dataset-owner")
            val directory = writeDatasetDirectory()

            restore(
                db = db,
                fixture = fixture,
                dataset = PerfDataset(directory.absolutePath, PerfDataset.Form.Directory),
                owner = owner,
            )

            val tasks = db.taskDao().listAllForUser(owner.value)
            assertEquals(2, tasks.size, "the restored database holds ${tasks.size} tasks")
            assertEquals("First task", tasks.first { it.id == FIRST_TASK_ID }.title)

            val tagIds = db.taskDao().getTagIdsForUser(FIRST_TASK_ID, owner.value).first()
            assertEquals(listOf(TAG_ID), tagIds, "the restored task kept its tag")
        }

    /**
     * Writes a dataset directory the way the generator writes one: the manifest
     * and the payload, uncompressed, and nothing else.
     */
    private fun writeDatasetDirectory(): File {
        val payload =
            BackupPayload(
                schemaVersion = BackupFormat.SCHEMA_VERSION,
                tasks =
                    listOf(
                        TaskDto(
                            id = FIRST_TASK_ID,
                            title = "First task",
                            createdAt = 1_000L,
                            updatedAt = 1_000L,
                        ),
                        TaskDto(
                            id = SECOND_TASK_ID,
                            title = "Second task",
                            createdAt = 2_000L,
                            updatedAt = 2_000L,
                        ),
                    ),
                tags =
                    listOf(
                        TagDto(
                            id = TAG_ID,
                            name = "work",
                            color = 0xFF336699.toInt(),
                            createdAt = 1_000L,
                            updatedAt = 1_000L,
                        ),
                    ),
                taskTags = listOf(TaskTagDto(taskId = FIRST_TASK_ID, tagId = TAG_ID)),
            )
        val payloadBytes = StableJson.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
        val manifest =
            BackupDomain.buildManifest(
                appVersion = "test",
                nowEpochMillis = 0L,
                userId = UserId.anonymous,
                payloadBytes = payloadBytes,
                counts = BackupDomain.countsOf(payload),
            )
        val manifestBytes = StableJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()

        val directory = File(fixture.directory(), "dataset").apply { mkdirs() }
        File(directory, BackupFormat.ENTRY_MANIFEST).writeBytes(manifestBytes)
        File(directory, BackupFormat.ENTRY_PAYLOAD).writeBytes(payloadBytes)
        return directory
    }

    private companion object {
        const val FIRST_TASK_ID = "task-first"
        const val SECOND_TASK_ID = "task-second"
        const val TAG_ID = "tag-work"
    }
}
