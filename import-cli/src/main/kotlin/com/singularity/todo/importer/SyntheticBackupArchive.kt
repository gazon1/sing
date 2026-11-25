package com.singularity.todo.importer

import com.singularity.todo.core.backup.BackupDomain
import com.singularity.todo.core.backup.BackupManifest
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.version.appVersion

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
}
