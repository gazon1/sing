package com.singularity.todo.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * JVM platform implementation of [FileSystem] backed by [java.io.File].
 */
class JvmFileSystem : FileSystem {
    override suspend fun readBytes(path: String): ByteArray = File(path).readBytes()

    override suspend fun writeBytes(
        path: String,
        data: ByteArray,
    ) {
        File(path).parentFile?.mkdirs()
        File(path).writeBytes(data)
    }

    /**
     * The rename, which is atomic within one filesystem.
     *
     * `Files.move` is the platform's own rename: the target is replaced and the
     * source is gone. `ATOMIC_MOVE` is what makes it *one* rename — without it
     * the platform unlinks the target first, so there is a window in which the
     * target is not there at all, and a reader can find it missing.
     *
     * A move across two filesystems cannot be done by a rename, and a copy is
     * not atomic, so the platform refuses it rather than quietly copying.
     */
    override suspend fun move(
        from: String,
        to: String,
    ) {
        val target = Paths.get(to)
        target.parent?.let { Files.createDirectories(it) }
        Files.move(
            Paths.get(from),
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
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
            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int = stream.read(buffer, offset, length)

            override fun close() = stream.close()
        }
    }

    override fun openWrite(path: String): ByteSink {
        val file = File(path)
        file.parentFile?.mkdirs()
        val stream = file.outputStream()
        return object : ByteSink {
            override fun write(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ) {
                stream.write(buffer, offset, length)
            }

            // Closing is what flushes the last of the archive to the disk.
            override fun close() = stream.close()
        }
    }
}
