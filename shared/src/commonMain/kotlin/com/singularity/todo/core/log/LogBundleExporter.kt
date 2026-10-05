package com.singularity.todo.core.log

import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.platform.nowInSystemZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.Padding

/**
 * Collects the rolling log files produced by [FileLogWriter] into a single
 * ZIP archive suitable for export via [FileSharePort][com.singularity.todo.core.files.FileSharePort].
 *
 * This class is stateless — it reads log files and writes the archive without
 * modifying the originals.
 *
 * ## Platform lifecycle note
 *
 * On Android, [FileLogWriter.beginShutdown] is never called, so the final log
 * buffer may not be flushed to disk. Entries written after the last explicit
 * flush are not included in the export.
 *
 * ## Redaction note
 *
 * [RedactingLogWriter] does not currently redact `cause` chain or `tag` fields.
 * Exported logs may contain sensitive data in these fields.
 */
open class LogBundleExporter(
    private val logDirectory: String,
    private val backupCodec: BackupCodec,
    private val fileSystem: FileSystem,
    /**
     * Number of rolling log files. Shares [LOG_FILE_COUNT] with [FileLogWriter] so the
     * two cannot drift — a hardcoded copy here silently truncated exported bundles
     * whenever the writer's retention changed.
     */
    private val fileCount: Int = LOG_FILE_COUNT,
) {
    /**
     * Creates a ZIP archive containing all existing log files.
     *
     * @return the path to the created archive, or a failure if the archive
     *   could not be written.
     */
    open suspend fun export(): Result<String> = runCatchingCancellable {
        val timestamp = BUNDLE_STAMP.format(nowInSystemZone())
        val archiveName = "singularity-logs-$timestamp.zip"
        val archivePath = "$logDirectory/$archiveName"

        val files = collectLogFiles()
        val manifestBytes = """{"type":"log-bundle","timestamp":"$timestamp"}""".toByteArray()
        val payloadBytes = buildPayload(files)

        backupCodec.export(
            manifestBytes = manifestBytes,
            payloadBytes = payloadBytes,
            attachments = files,
            destPath = archivePath,
            fs = fileSystem,
        ).getOrThrow()

        archivePath
    }

    private fun buildPayload(files: List<Pair<String, ByteArray>>): ByteArray {
        val sb = StringBuilder()
        sb.append("""{"logs":[""")
        files.forEachIndexed { idx, (name, data) ->
            if (idx > 0) sb.append(",")
            sb.append("""{"name":"$name","size":${data.size}}""")
        }
        sb.append("]}")
        return sb.toString().toByteArray()
    }

    private suspend fun collectLogFiles(): List<Pair<String, ByteArray>> {
        val files = (0 until fileCount)
            .map { "log.$it.txt" }
            .map { "$logDirectory/$it" }
            .filter { fileSystem.exists(it) }
            .map { path -> path.substringAfterLast('/') to fileSystem.readBytes(path) }
        return files
    }

    private companion object {
        /**
         * `yyyyMMdd-HHmmss` in the device's own time zone.
         *
         * Every field is explicitly [Padding.ZERO] rather than left to the default:
         * the default is "no padding", and a bundle exported at 09:04 would then be
         * named `…-90412` — still unique, but no longer lexically sortable against the
         * bundles around it, which is the only reason a timestamp is in the name.
         *
         * The zone is the device's, matching what the log lines inside the bundle say.
         * A UTC stamp would have made an export filed under one day and read under
         * another.
         */
        val BUNDLE_STAMP: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
            year(Padding.ZERO)
            monthNumber(Padding.ZERO)
            day(Padding.ZERO)
            chars("-")
            hour(Padding.ZERO)
            minute(Padding.ZERO)
            second(Padding.ZERO)
        }
    }
}
