package com.singularity.todo.core.backup

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSource
import com.singularity.todo.core.files.FileSystem
import java.io.File

/**
 * Reads a generated dataset directory the way an archive reader reads an archive.
 *
 * ## Why the codec is bound to the directory
 *
 * The codec port hands the importer one byte stream ([FileSource]) — an archive
 * is one file. A dataset directory is two files (`manifest.json` and
 * `payload.json`), so the port cannot carry it, and this codec is bound to the
 * directory it reads instead.
 *
 * That is the whole difference from [JvmBackupCodec]: the archive budget is not
 * applied here. The budget is the app's ceiling for what *the app* reads — a
 * backup restored through the system file picker — and a dataset directory is not
 * a backup: nothing in the app reads one. The production budget and its
 * validation stay untouched (#3197).
 *
 * Everything else is the production path: [BackupImporter] validates the
 * manifest, migrates the payload and applies the rows exactly as it does for an
 * archive.
 */
class DatasetDirectoryBackupCodec(private val directory: File) : BackupCodec {
    override suspend fun export(
        manifestBytes: ByteArray,
        payloadBytes: ByteArray,
        attachments: List<Pair<String, String>>,
        destPath: String,
        fs: FileSystem,
    ): Result<Unit> =
        Result.failure(
            UnsupportedOperationException(
                "a dataset directory is written by the generator, not exported here: ${directory.absolutePath}",
            ),
        )

    override suspend fun import(
        sourcePath: String,
        fs: FileSystem,
    ): Result<BackupCodec.CodecReadResult> = read()

    override suspend fun importFromSource(source: FileSource): Result<BackupCodec.CodecReadResult> = read()

    private fun read(): Result<BackupCodec.CodecReadResult> =
        runCatchingCancellable {
            val manifest = File(directory, BackupFormat.ENTRY_MANIFEST)
            val payload = File(directory, BackupFormat.ENTRY_PAYLOAD)
            check(manifest.isFile) {
                "the dataset directory has no ${BackupFormat.ENTRY_MANIFEST}: ${directory.absolutePath}"
            }
            check(payload.isFile) {
                "the dataset directory has no ${BackupFormat.ENTRY_PAYLOAD}: ${directory.absolutePath}"
            }
            BackupCodec.CodecReadResult(
                manifestBytes = manifest.readBytes(),
                payloadBytes = payload.readBytes(),
                attachments = emptyMap(),
            )
        }
}
