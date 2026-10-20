package com.singularity.todo.core.observability

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * The Android writer for tool-call records: append-only JSONL inside the app's own
 * private storage.
 *
 * The same contract as the JVM writer — one file per process per day, append never
 * truncate, failures are not fatal — with two differences that come from the platform:
 *
 * * There is no process id to name the file with, so a random token is generated once
 *   per writer instead. The reader merges the files, so the name does not matter.
 * * The directory is the app's private storage, which the platform already keeps away
 *   from other applications, so there are no permissions to set.
 */
class AndroidFileToolCallLog(
    private val directory: String,
    private val processToken: String = Random.nextLong().toString(16),
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
    }

    /**
     * Removes the records of the days before the retention window.
     *
     * The age of a file is read from its *name* — the day is the first part of it —
     * because the modification time of a file that is still open is the time it was
     * last written, and today's file is always the one being written to.
     */
    fun purgeExpired(retentionDays: Int = RETENTION_DAYS) {
        val cutoff = Instant.fromEpochMilliseconds(nowEpochMillis())
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(SUFFIX)) return@forEach
            val day = runCatching { LocalDate.parse(file.name.substringBefore('-')) }.getOrNull()
                ?: return@forEach
            if (day < cutoff.minus(retentionDays, DateTimeUnit.DAY)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun fileFor(epochMillis: Long): File {
        val day = Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
        return File(dir, "$day-$processToken$SUFFIX")
    }

    companion object {
        const val SUFFIX = ".jsonl"
        const val RETENTION_DAYS = 30
    }
}
