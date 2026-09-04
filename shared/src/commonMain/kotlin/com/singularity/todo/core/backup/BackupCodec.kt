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
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CodecReadResult

            if (!manifestBytes.contentEquals(other.manifestBytes)) return false
            if (!payloadBytes.contentEquals(other.payloadBytes)) return false
            if (attachments != other.attachments) return false

            return true
        }

        override fun hashCode(): Int {
            var result = manifestBytes.contentHashCode()
            result = 31 * result + payloadBytes.contentHashCode()
            result = 31 * result + attachments.hashCode()
            return result
        }
    }
}
