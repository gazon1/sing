package com.singularity.todo.core.backup

import com.singularity.todo.core.files.FileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Android implementation using java.util.zip (available since API 1).
 */
class AndroidBackupCodec : BackupCodec {

    override suspend fun export(
        manifestBytes: ByteArray,
        payloadBytes: ByteArray,
        attachments: List<Pair<String, ByteArray>>,
        destPath: String,
        fs: FileSystem,
    ): Result<Unit> = runCatching {
        fs.ensureDir(destPath.substringBeforeLast('/', ""))

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
            zos.write(manifestBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(BackupFormat.ENTRY_PAYLOAD))
            zos.write(payloadBytes)
            zos.closeEntry()

            for ((name, data) in attachments) {
                zos.putNextEntry(ZipEntry(BackupFormat.DIR_ATTACHMENTS + name))
                zos.write(data)
                zos.closeEntry()
            }
        }

        fs.writeBytes(destPath, baos.toByteArray())
    }

    override suspend fun import(sourcePath: String, fs: FileSystem): Result<BackupCodec.CodecReadResult> = runCatching {
        if (!fs.exists(sourcePath)) throw BackupError.FileNotFound(sourcePath)
        val bytes = fs.readBytes(sourcePath)

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

        BackupCodec.CodecReadResult(
            manifestBytes = manifest
                ?: throw BackupError.MalformedManifest("missing manifest.json"),
            payloadBytes = payload
                ?: throw BackupError.MalformedManifest("missing payload.json"),
            attachments = attachments,
        )
    }
}
