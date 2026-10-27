package com.singularity.todo.core.files

import java.io.File

/**
 * JVM platform implementation of [FileSystem] backed by [java.io.File].
 */
class JvmFileSystem : FileSystem {
    override suspend fun readBytes(path: String): ByteArray = File(path).readBytes()

    override suspend fun writeBytes(path: String, data: ByteArray) {
        File(path).parentFile?.mkdirs()
        File(path).writeBytes(data)
    }

    /**
     * The rename, which is atomic within one filesystem.
     *
     * A rename across two filesystems cannot be done by a rename, and a copy is
     * not atomic — but the app's own files are all in one place, so the copy is
     * the exception rather than the rule.
     */
    override suspend fun move(from: String, to: String) {
        val source = File(from)
        val target = File(to)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    override suspend fun delete(path: String): Boolean = File(path).delete()

    override suspend fun exists(path: String): Boolean = File(path).exists()

    override suspend fun ensureDir(dir: String) {
        File(dir).mkdirs()
    }

    override suspend fun listDir(dir: String): List<String> {
        val f = File(dir)
        return if (f.exists() && f.isDirectory) {
            f.listFiles()?.map { it.absolutePath } ?: emptyList()
        } else {
            emptyList()
        }
    }

    override suspend fun stat(path: String): FileStat? {
        val f = File(path)
        return if (f.exists()) {
            FileStat(f.absolutePath, f.lastModified(), f.length(), f.isDirectory)
        } else {
            null
        }
    }

    override fun openRead(path: String): ByteSource {
        val stream = File(path).inputStream()
        return object : ByteSource {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                stream.read(buffer, offset, length)

            override fun close() = stream.close()
        }
    }

    override fun openWrite(path: String): ByteSink {
        val file = File(path)
        file.parentFile?.mkdirs()
        val stream = file.outputStream()
        return object : ByteSink {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                stream.write(buffer, offset, length)
            }

            // Closing is what flushes the last of the archive to the disk.
            override fun close() = stream.close()
        }
    }
}
