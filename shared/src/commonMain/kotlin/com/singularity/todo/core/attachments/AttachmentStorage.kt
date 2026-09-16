package com.singularity.todo.core.attachments

import com.singularity.todo.core.files.FileChecksum
import com.singularity.todo.core.files.FileSystem

class AttachmentStorage(private val fs: FileSystem, private val dir: String) {
    suspend fun saveFile(taskId: String, id: String, sourcePath: String, ext: String): Result<String> = runCatching {
        val target = AttachmentDomain.buildLocalPath(dir, taskId, id, ext)
        val data = fs.readBytes(sourcePath)
        fs.ensureDir("$dir/$taskId")
        fs.writeBytes(target, data)
        target
    }

    suspend fun saveBytes(taskId: String, id: String, data: ByteArray, ext: String): Result<String> = runCatching {
        val target = AttachmentDomain.buildLocalPath(dir, taskId, id, ext)
        fs.ensureDir("$dir/$taskId")
        fs.writeBytes(target, data)
        target
    }

    suspend fun computeChecksum(localPath: String): Result<String> = runCatching {
        FileChecksum.sha256(fs.readBytes(localPath))
    }

    suspend fun deleteFile(path: String): Result<Boolean> = runCatching {
        fs.delete(path)
    }

    suspend fun fileExists(path: String): Boolean = fs.exists(path)
}
