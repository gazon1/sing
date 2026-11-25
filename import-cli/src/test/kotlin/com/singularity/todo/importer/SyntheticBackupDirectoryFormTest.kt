package com.singularity.todo.importer

import com.singularity.todo.core.backup.ArchiveBudget
import com.singularity.todo.core.backup.BackupFormat
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The dataset directory: the same dataset, without the archive around it.
 *
 * The archive budget is the app's ceiling for what the app reads — a backup
 * restored through the system file picker. The `100k` profile with tag rows does
 * not fit under it, and the generator used to refuse to produce such a dataset
 * at all (#3197). The way out is not a bigger budget: it is a form that is not a
 * backup. A dataset directory holds the same `manifest.json` and `payload.json`
 * uncompressed, and nothing in the app reads it — so the budget does not apply
 * to it.
 *
 * These tests hold the two claims the form rests on: the directory carries the
 * same bytes the archive would, and a dataset the archive refuses is written all
 * the same.
 */
class SyntheticBackupDirectoryFormTest {
    @Test
    fun `the directory form writes the bytes the archive form writes`() =
        runTest {
            val params = SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_1K, seed = MEASUREMENT_SEED)
            val archiveFile = workDir("archive").resolve("dataset.zip").also { it.parentFile.mkdirs() }
            val directory = workDir("directory")

            val archive = SyntheticBackupArchive.write(params, archiveFile.absolutePath)
            val dataset = SyntheticBackupArchive.writeDirectory(params, directory.absolutePath)

            assertContentEquals(
                archive.payloadBytes,
                dataset.payloadBytes,
                "the dataset directory must carry the payload the archive carries",
            )
            assertContentEquals(
                archive.manifestBytes,
                dataset.manifestBytes,
                "the dataset directory must carry the manifest the archive carries",
            )
            assertContentEquals(
                dataset.payloadBytes,
                File(directory, BackupFormat.ENTRY_PAYLOAD).readBytes(),
                "the payload on disk must be the payload that was generated",
            )
            assertContentEquals(
                dataset.manifestBytes,
                File(directory, BackupFormat.ENTRY_MANIFEST).readBytes(),
                "the manifest on disk must be the manifest that was generated",
            )
        }

    @Test
    fun `a dataset the archive refuses is written as a directory`() =
        runTest {
            val params =
                SyntheticBackupGenerator
                    .defaultParams(GenerateProfile.TASKS_100K, seed = MEASUREMENT_SEED)
                    .copy(tags = 1)
            val archiveFile = workDir("over-budget").resolve("dataset.zip").also { it.parentFile.mkdirs() }
            val directory = workDir("over-budget-directory")

            val refused = runCatching { SyntheticBackupArchive.write(params, archiveFile.absolutePath) }
            assertTrue(
                refused.isFailure,
                "the archive form must still refuse a dataset over the budget",
            )

            SyntheticBackupArchive.writeDirectory(params, directory.absolutePath)
            val payload = File(directory, BackupFormat.ENTRY_PAYLOAD)
            assertTrue(
                payload.length() > ArchiveBudget.MAX_TOTAL_BYTES,
                "the dataset must be over the budget, or it proves nothing: ${payload.length()} bytes",
            )
        }

    /** A directory of this test's own, so a run leaves nothing behind. */
    private fun workDir(name: String): File =
        File("build/tmp/dataset-form-$name").apply {
            deleteRecursively()
            mkdirs()
        }

    private companion object {
        /** The seed the datasets were measured with. */
        const val MEASUREMENT_SEED = 4242L
    }
}
