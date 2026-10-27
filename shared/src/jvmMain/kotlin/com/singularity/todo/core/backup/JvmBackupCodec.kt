package com.singularity.todo.core.backup

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.files.FileSource
import com.singularity.todo.core.files.FileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * JVM implementation using java.util.zip.
 *
 * ## Why the archive is written in pieces
 *
 * The zip goes into the destination through `fs.openWrite`, not through a buffer:
 * an account with attachments is larger than a phone's memory, and a codec that
 * built the whole archive in a `ByteArrayOutputStream` first would hold it there
 * a second time. Each attachment is copied from its own file in chunks, so
 * neither the archive nor an attachment is ever whole in memory here.
 */
class JvmBackupCodec : BackupCodec {

    override suspend fun export(
        manifestBytes: ByteArray,
        payloadBytes: ByteArray,
        attachments: List<Pair<String, String>>,
        destPath: String,
        fs: FileSystem,
    ): Result<Unit> = runCatchingCancellable {
        fs.ensureDir(destPath.substringBeforeLast('/', ""))

        // The sink is closed in `finally`, not with `use`: `ByteSink` is the
        // project's own port and does not implement `Closeable`, and closing it is
        // what makes the bytes real (see `ByteSink.close`).
        val sink = fs.openWrite(destPath)
        try {
            ZipOutputStream(SinkOutputStream(sink)).use { zos ->
                zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
                zos.write(manifestBytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_PAYLOAD))
                zos.write(payloadBytes)
                zos.closeEntry()

                for ((name, sourcePath) in attachments) {
                    zos.putNextEntry(ZipEntry(BackupFormat.DIR_ATTACHMENTS + name))
                    copyInto(zos, fs.openRead(sourcePath))
                    zos.closeEntry()
                }
            }
        } finally {
            sink.close()
        }
    }

    override suspend fun import(
        sourcePath: String,
        fs: FileSystem,
    ): Result<BackupCodec.CodecReadResult> = runCatchingCancellable {
        if (!fs.exists(sourcePath)) throw BackupStorageError.FileNotFound(sourcePath)
        val source = fs.openRead(sourcePath)
        try {
            decodeZip(source.asInputStream())
        } finally {
            source.close()
        }
    }

    override suspend fun importFromSource(
        source: FileSource,
    ): Result<BackupCodec.CodecReadResult> = runCatchingCancellable {
        val bytes = source.readBytes()
        decodeZip(ByteArrayInputStream(bytes))
    }

    private fun decodeZip(stream: java.io.InputStream): BackupCodec.CodecReadResult {
        var manifest: ByteArray? = null
        var payload: ByteArray? = null
        val attachments = mutableMapOf<String, ByteArray>()

        ZipInputStream(stream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val data = zis.readBytes()
                when (entry.name) {
                    BackupFormat.ENTRY_MANIFEST -> manifest = data

                    BackupFormat.ENTRY_PAYLOAD -> payload = data

                    else -> if (entry.name.startsWith(BackupFormat.DIR_ATTACHMENTS)) {
                        attachments[entry.name.removePrefix(BackupFormat.DIR_ATTACHMENTS)] = data
                    }
                }
                entry = zis.nextEntry
            }
        }

        return BackupCodec.CodecReadResult(
            manifestBytes = manifest
                ?: throw BackupPayloadError.MalformedManifest("missing manifest.json"),
            payloadBytes = payload
                ?: throw BackupPayloadError.MalformedManifest("missing payload.json"),
            attachments = attachments,
        )
    }

    /** Adapts the project's [ByteSource] to a java [InputStream] for streaming zip read. */
    private fun ByteSource.asInputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val buf = ByteArray(1)
            val n = this@asInputStream.read(buf, 0, 1)
            return if (n < 0) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            this@asInputStream.read(b, off, len)

        override fun close() = this@asInputStream.close()
    }

    /** Copies everything [source] yields into [out], in pieces, and closes it. */
    private fun copyInto(out: OutputStream, source: ByteSource) {
        try {
            val buffer = ByteArray(COPY_CHUNK_BYTES)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        } finally {
            source.close()
        }
    }

    /**
     * A [ByteSink] as an [OutputStream].
     *
     * `ZipOutputStream` writes through `OutputStream`, and the project's sink is
     * the shape `commonMain` can name. This is the adapter between the two, and it
     * is per-platform because the stream is.
     */
    private class SinkOutputStream(private val sink: ByteSink) : OutputStream() {
        override fun write(b: Int) {
            val one = byteArrayOf(b.toByte())
            sink.write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) = sink.write(b, off, len)
    }

    private companion object {
        /** How much of an attachment is copied at a time. */
        const val COPY_CHUNK_BYTES = 64 * 1024
    }
}
