package com.singularity.todo.importer

import com.singularity.todo.core.backup.BackupDomain
import com.singularity.todo.core.backup.BackupFormat
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.version.appVersion
import java.io.File

/**
 * The archive a generated dataset was written as.
 *
 * The bytes travel with it because the manifest is a checksum over the
 * payload: a caller that wants to report the archive it wrote has to report
 * the bytes the checksum was taken over.
 */
class SyntheticArchive(
    val dataset: GeneratedDataset,
    val manifest: BackupManifest,
    val payloadBytes: ByteArray,
    val manifestBytes: ByteArray,
)

/**
 * Writes a generated dataset as a native backup archive.
 *
 * The archive is what the app reads back: the CLI restores one, the phone
 * restores one through the system file picker, and the desktop restores one
 * into its own database. Writing it here — once — is what keeps the datasets
 * of the platforms the same dataset: a caller that builds its own archive
 * would measure a dataset no other platform ever saw.
 */
object SyntheticBackupArchive {
    /**
     * Generates the dataset, checks that it fits an archive, and writes it.
     *
     * The archive holds the manifest and the payload, exactly as the app's own
     * export writes them, so the file that comes out can be restored by the
     * app without knowing anything about the generator.
     */
    suspend fun write(
        params: GenerateParams,
        destination: String,
    ): SyntheticArchive {
        val generated = SyntheticBackupGenerator.generate(params)
        SyntheticBackupValidator.validate(generated.payload)

        val payloadBytes =
            StableJson.encodeToString(BackupPayload.serializer(), generated.payload).encodeToByteArray()
        val manifest =
            BackupDomain.buildManifest(
                appVersion = appVersion().name,
                nowEpochMillis = 0L,
                userId = UserId.anonymous,
                payloadBytes = payloadBytes,
                counts = BackupDomain.countsOf(generated.payload),
            )
        val manifestBytes = StableJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()
        SyntheticBackupValidator.ensureWithinArchiveBudget(payloadBytes, manifestBytes)

        JvmBackupCodec()
            .export(manifestBytes, payloadBytes, emptyList(), destination, JvmFileSystem())
            .getOrThrow()
        return SyntheticArchive(generated, manifest, payloadBytes, manifestBytes)
    }

    /**
     * Writes the dataset as a directory of two JSON files, instead of as an archive.
     *
     * ## Why this is not the archive path
     *
     * The backup archive budget belongs to what the *app* reads: a backup the
     * phone restores through the file picker. A dataset directory is not a backup
     * — the app cannot restore it, and nothing in the app reads it — so the
     * budget does not apply to it, and the two entries here are the same
     * `manifest.json` and `payload.json` the archive holds, written out
     * uncompressed and unbounded.
     *
     * The ceiling this removes is real: the 100k profile's task rows alone are
     * 98% of the archive budget, so the largest profile could carry no tag rows,
     * no notes and no checklist items at all (#3197).
     *
     * What stays true: the same generator, the same seed, the same bytes. The
     * payload written here is byte-for-byte the payload the archive form writes,
     * and the manifest is the same manifest — the checksum in it covers the same
     * bytes. A dataset and an archive of the same params are the same dataset.
     */
    suspend fun writeDirectory(
        params: GenerateParams,
        destinationDir: String,
    ): SyntheticArchive {
        val generated = SyntheticBackupGenerator.generate(params)
        SyntheticBackupValidator.validate(generated.payload)

        val payloadBytes =
            StableJson.encodeToString(BackupPayload.serializer(), generated.payload).encodeToByteArray()
        val manifest =
            BackupDomain.buildManifest(
                appVersion = appVersion().name,
                nowEpochMillis = 0L,
                userId = UserId.anonymous,
                payloadBytes = payloadBytes,
                counts = BackupDomain.countsOf(generated.payload),
            )
        val manifestBytes = StableJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()

        val directory = File(destinationDir)
        directory.mkdirs()
        File(directory, BackupFormat.ENTRY_MANIFEST).writeBytes(manifestBytes)
        File(directory, BackupFormat.ENTRY_PAYLOAD).writeBytes(payloadBytes)
        return SyntheticArchive(generated, manifest, payloadBytes, manifestBytes)
    }
}
