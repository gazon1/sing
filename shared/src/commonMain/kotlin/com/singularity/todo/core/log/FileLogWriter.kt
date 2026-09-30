@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.core.log

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.utf8Size
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

private const val TAG = "FileLogWriter"

/**
 * A [LogWriter] that persists logs to rolling files under [logDirectory].
 *
 * - Rolls over at [fileSizeLimit] into up to [fileCount] files (default: 4 × 2 MB).
 * - File 0 is always the current one; older entries shift to file N+1 on rotation.
 * - **Appends to `log.0`** on startup instead of rotating — no log loss between restarts.
 * - Thread-safe: all writes go through a single-threaded [CoroutineScope] on [Dispatchers.IO].
 * - Graceful shutdown: [beginShutdown] drains the buffer within [DRAIN_TIMEOUT_MS]
 *   before returning; constructor parameter [fileSystem] enables testing with in-memory FS.
 *
 * **Format** per line:
 * ```
 * 2024-01-23T14:32:01.234Z ClassName I message here
 * ```
 * Tag is truncated/padded to 23 characters. Stack traces follow the message line.
 */
class FileLogWriter(
    val logDirectory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val fileSizeLimit: Long = DEFAULT_FILE_SIZE_LIMIT,
    private val fileCount: Int = DEFAULT_FILE_COUNT,
) : LogWriter() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val files = List(fileCount) { logDirectory.resolve("log.$it.txt") }
    private var written: Long
    private var sink: BufferedSink

    @Volatile
    private var shuttingDown = false

    init {
        fileSystem.createDirectories(logDirectory)
        val currentFile = files[0]
        // Initialize counter from existing file size so rotation triggers at the
        // correct byte boundary after a restart, not from 0.
        written = fileSystem.metadataOrNull(currentFile)?.size ?: 0L
        // Use appendingSink so restart does not truncate existing content.
        sink = fileSystem.appendingSink(currentFile).buffer()
    }

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val entry = formatEntry(Clock.System.now(), severity, tag, message, throwable)
        val writeJob = scope.launch { write(entry) }
        if (shuttingDown) {
            @Suppress("NoRunBlocking") // process-exit drain: no coroutine context available
            runBlocking { writeJob.join() }
        }
    }

    /** Blocks until all buffered entries are flushed to disk, up to [DRAIN_TIMEOUT_MS]. */
    fun beginShutdown() {
        shuttingDown = true
        @Suppress("NoRunBlocking") // blocking close() contract — drain before stream teardown
        runBlocking { withTimeoutOrNull(DRAIN_TIMEOUT_MS) { flush() } }
    }

    /** Returns the paths of all existing log files (oldest to newest). */
    fun logFiles(): List<Path> = files.filter { fileSystem.exists(it) }

    private suspend fun flush() {
        scope.launch { }.join()
    }

    private fun write(entry: String) {
        runCatching {
            sink.writeUtf8(entry)
            sink.flush()
            written += entry.utf8Size()
            if (written >= fileSizeLimit) {
                sink.close()
                sink = rotate()
            }
        }.onFailure { e ->
            // Do not crash the app when disk is full — log the failure and continue.
            co.touchlab.kermit.Logger.e(TAG) { "Failed to write log entry: ${e.message}" }
        }
    }

    private fun rotate(): BufferedSink {
        for (i in fileCount - 2 downTo 0) {
            if (fileSystem.exists(files[i])) {
                fileSystem.delete(files[i + 1])
                fileSystem.atomicMove(files[i], files[i + 1])
            }
        }
        written = 0
        return fileSystem.appendingSink(files[0]).buffer()
    }

    private companion object {
        const val DEFAULT_FILE_SIZE_LIMIT = 2L * 1024 * 1024
        const val DEFAULT_FILE_COUNT = 4
        const val DRAIN_TIMEOUT_MS = 2_000L

        private const val MAX_TAG_LENGTH = 23

        private val TIMESTAMP_FORMAT = kotlinx.datetime.LocalDateTime.Format {
            year()
            char('-')
            monthNumber()
            char('-')
            day()
            char('T')
            hour()
            char(':')
            minute()
            char(':')
            second()
            char('.')
            secondFraction(3)
            char('Z')
        }

        private fun formatEntry(
            timestamp: Instant,
            severity: Severity,
            tag: String,
            message: String,
            throwable: Throwable?,
        ): String = buildString {
            append(TIMESTAMP_FORMAT.format(timestamp.toLocalDateTime(TimeZone.UTC)))
            append(' ')
            append(truncateTag(tag))
            append(' ')
            append(severity.name[0])
            append(' ')
            append(message)
            append('\n')
            throwable?.let {
                append(it.stackTraceToString())
                append('\n')
            }
        }

        private fun truncateTag(tag: String): String {
            val t = tag.take(MAX_TAG_LENGTH)
            return when {
                t.length == MAX_TAG_LENGTH -> t
                t.length < MAX_TAG_LENGTH -> t.padEnd(MAX_TAG_LENGTH, ' ')
                else -> t
            }
        }
    }
}
