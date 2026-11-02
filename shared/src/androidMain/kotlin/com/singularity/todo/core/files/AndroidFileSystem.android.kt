package com.singularity.todo.core.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.singularity.todo.core.error.runCatchingCancellable
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class AndroidFileSystem(private val context: Context) : FileSystem {
    override suspend fun readBytes(path: String): ByteArray =
        if (path.startsWith("content:")) {
            context.contentResolver.openInputStream(Uri.parse(path))!!.use { it.readBytes() }
        } else {
            File(path).readBytes()
        }

    override suspend fun writeBytes(
        path: String,
        data: ByteArray,
    ) {
        if (path.startsWith("content:")) {
            context.contentResolver.openOutputStream(Uri.parse(path))!!.use { it.write(data) }
        } else {
            File(path).parentFile?.mkdirs()
            File(path).writeBytes(data)
        }
    }

    override suspend fun exists(path: String): Boolean =
        if (path.startsWith("content:")) {
            runCatchingCancellable {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.close()
            }.isSuccess
        } else {
            File(path).exists()
        }

    override suspend fun stat(path: String): FileStat? =
        if (path.startsWith("content:")) {
            val uri = Uri.parse(path)
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            if (cursor == null) {
                FileStat(path, System.currentTimeMillis(), 0L, false)
            } else {
                cursor.use { c ->
                    val size =
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.SIZE)
                            if (idx >= 0) {
                                c.getLong(idx)
                            } else {
                                0L
                            }
                        } else {
                            0L
                        }
                    FileStat(path, System.currentTimeMillis(), size, false)
                }
            }
        } else {
            val f = File(path)
            if (f.exists()) {
                FileStat(f.absolutePath, f.lastModified(), f.length(), f.isDirectory)
            } else {
                null
            }
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

    override fun openRead(path: String): ByteSource {
        val stream =
            if (path.startsWith("content:")) {
                context.contentResolver.openInputStream(Uri.parse(path))!!
            } else {
                File(path).inputStream()
            }
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
        val stream =
            if (path.startsWith("content:")) {
                context.contentResolver.openOutputStream(Uri.parse(path))!!
            } else {
                val file = File(path)
                file.parentFile?.mkdirs()
                file.outputStream()
            }
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
