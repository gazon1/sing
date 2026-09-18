package com.singularity.todo.core.backup

import com.singularity.todo.core.files.FileSystem

/**
 * Port for zip-based backup encode/decode.
 *
 * Zip structure: `MANIFEST.json` + `payload.json` + `attachments/$name` entries.
 * Both manifest and payload are JSON; attachments are raw binary files.
 *
 * Implementations: JvmBackupCodec (JVM), AndroidBackupCodec (Android).
 */
interface BackupCodec {
    /**
     * Creates a zip archive at `destPath` containing:
     * - `MANIFEST.json` — [manifestBytes]
     * - `payload.json` — [payloadBytes]
     * - `attachments/$name` — one entry per [attachments] pair (name → bytes)
     */
    suspend fun export(
        manifestBytes: ByteArray,
        payloadBytes: ByteArray,
        attachments: List<Pair<String, ByteArray>>,
        destPath: String,
        fs: FileSystem,
    ): Result<Unit>

    /**
     * Reads and parses the zip backup at [sourcePath].
     * Returns the decoded manifest, payload, and all attachment entries.
     */
    suspend fun import(sourcePath: String, fs: FileSystem): Result<CodecReadResult>

    data class CodecReadResult(
        val manifestBytes: ByteArray,
        val payloadBytes: ByteArray,
        val attachments: Map<String, ByteArray>,
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
