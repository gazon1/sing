package com.singularity.todo.core.observability

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The JVM writer for tool-call records: append-only JSONL, one file per process per day.
 *
 * ## Why a file per process
 *
 * Two processes can write into the same directory — the desktop app and an MCP server
 * on the same machine, or two MCP servers. A file per process removes the question of
 * interleaving entirely: no lock, no shared offset, no two writers in one file. The
 * reader merges the files, so the split is invisible to the report.
 *
 * ## The rules
 *
 * * **Append, never truncate.** A second invocation in the same process must not
 *   destroy the first one's records, so the file is opened in append mode and the
 *   rotation below only ever removes files from *previous* days.
 * * **Owner-only.** A payload-free record still says what was done, by whom, and when.
 * * **Failures are not fatal.** The caller swallows them; the point of the record is
 *   to inform, not to obstruct.
 */
class JvmFileToolCallLog(
    private val directory: String,
    private val processToken: String = ProcessHandle.current().pid().toString(),
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : ToolCallLog {

    private val dir = File(directory)

    override fun record(event: ToolCallEvent) {
        val file = fileFor(event.ts)
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { stream ->
            stream.write((ToolCallEventFormat.toLine(event) + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
        }
        restrictToOwner(file)
    }

    /**
     * Removes the records of the days before the retention window.
     *
     * Called once, when the writer is created. Today's file is never a candidate — it is
     * the file being written to — so the writer cannot delete the file it holds open.
     */
    fun purgeExpired(retentionDays: Int = RETENTION_DAYS) {
        val cutoff = nowEpochMillis() - retentionDays * 24L * 60L * 60L * 1000L
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(SUFFIX)) return@forEach
            if (file.lastModified() >= cutoff) return@forEach
            runCatching { file.delete() }
        }
    }

    private fun fileFor(epochMillis: Long): File {
        val day = Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
        return File(dir, "$day-$processToken$SUFFIX")
    }

    private fun restrictToOwner(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                PosixFilePermissions.fromString("rw-------"),
            )
        }
    }

    companion object {
        const val SUFFIX = ".jsonl"
        const val RETENTION_DAYS = 30
    }
}
