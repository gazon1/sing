package com.singularity.todo.core.backup

import com.singularity.todo.core.files.FileSystem

/**
 * Interface for zip-based backup codec.
 * Implementations: JvmBackupCodec (JVM), AndroidBackupCodec (Android).
 */
interface BackupCodec {
    suspend fun export(
        manifestBytes: ByteArray,
        payloadBytes: ByteArray,
        attachments: List<Pair<String, ByteArray>>,
        destPath: String,
        fs: FileSystem
    ): Result<Unit>

    suspend fun import(sourcePath: String, fs: FileSystem): Result<CodecReadResult>

    data class CodecReadResult(
        val manifestBytes: ByteArray,
        val payloadBytes: ByteArray,
        val attachments: Map<String, ByteArray>
    )
}
