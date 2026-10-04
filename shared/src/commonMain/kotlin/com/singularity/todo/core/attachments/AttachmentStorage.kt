package com.singularity.todo.core.attachments

import com.singularity.todo.core.files.FileChecksum
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Stores attachment files on disk via the [FileSystem] port.
 *
 * Files are laid out at `$dir/$taskId/$id.$ext`. Directories are created
 * automatically on first write. Checksums use SHA-256.
 *
 * **Thread safety**: implementations must be safe for concurrent use from
 * multiple coroutines (all existing implementations are).
 */
class AttachmentStorage(private val fs: FileSystem, private val dir: String) {
    /**
     * Copies a file from `sourcePath` into attachment storage.
     * Returns the absolute local path on success.
     */
    suspend fun saveFile(
        taskId: String,
        id: String,
        sourcePath: String,
        ext: String,
    ): Result<String> = runCatchingCancellable {
        val target = AttachmentDomain.buildLocalPath(dir, taskId, id, ext)
        val data = fs.readBytes(sourcePath)
        fs.ensureDir("$dir/$taskId")
        fs.writeBytes(target, data)
        target
    }

    /**
     * Saves raw bytes directly into attachment storage.
     * Returns the absolute local path on success.
     */
    suspend fun saveBytes(
        taskId: String,
        id: String,
        data: ByteArray,
        ext: String,
    ): Result<String> = runCatchingCancellable {
        val target = AttachmentDomain.buildLocalPath(dir, taskId, id, ext)
        fs.ensureDir("$dir/$taskId")
        fs.writeBytes(target, data)
        target
    }

    /**
     * Computes SHA-256 checksum of the file at `localPath`.
     * Used for deduplication and integrity verification.
     */
    suspend fun computeChecksum(localPath: String): Result<String> = runCatchingCancellable {
        FileChecksum.sha256(fs.readBytes(localPath))
    }

    /** Deletes the file at `path`. Returns `true` if the file existed. */
    suspend fun deleteFile(path: String): Result<Boolean> = runCatchingCancellable {
        fs.delete(path)
    }

    /** Returns `true` if a file exists at `path`. */
    suspend fun fileExists(path: String): Boolean = fs.exists(path)
}
