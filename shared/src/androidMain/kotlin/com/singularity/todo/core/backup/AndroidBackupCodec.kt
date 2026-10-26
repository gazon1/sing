package com.singularity.todo.core.backup

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.ByteSink
import com.singularity.todo.core.files.ByteSource
import com.singularity.todo.core.files.FileSource
import com.singularity.todo.core.files.FileSystem
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Android implementation using java.util.zip (available since API 1).
 *
 * Same shape as [com.singularity.todo.core.backup.JvmBackupCodec]: the zip is
 * written through `fs.openWrite` and each attachment is copied from its own file
 * in chunks, so no whole archive or attachment is held in memory.
 */
class AndroidBackupCodec : BackupCodec {

    override suspend fun export(
        manifestBytes: ByteArray,
        payloadBytes: ByteArray,
        attachments: List<Pair<String, String>>,
        destPath: String,
        fs: FileSystem,
    ): Result<Unit> = runCatchingCancellable {
        fs.ensureDir(destPath.substringBeforeLast('/', ""))

        // `ByteSink` is the project's port, not `Closeable`: closed in `finally`.
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
        if (!fs.exists(sourcePath)) throw BackupError.FileNotFound(sourcePath)
        val bytes = fs.readBytes(sourcePath)
        decodeZip(bytes)
    }

    override suspend fun importFromSource(
        source: FileSource,
    ): Result<BackupCodec.CodecReadResult> = runCatchingCancellable {
        val bytes = source.readBytes()
        decodeZip(bytes)
    }

    private fun decodeZip(bytes: ByteArray): BackupCodec.CodecReadResult {
        var manifest: ByteArray? = null
        var payload: ByteArray? = null
        val attachments = mutableMapOf<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
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
                ?: throw BackupError.MalformedManifest("missing manifest.json"),
            payloadBytes = payload
                ?: throw BackupError.MalformedManifest("missing payload.json"),
            attachments = attachments,
        )
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

    /** A [ByteSink] as an [OutputStream], the shape `ZipOutputStream` takes. */
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
