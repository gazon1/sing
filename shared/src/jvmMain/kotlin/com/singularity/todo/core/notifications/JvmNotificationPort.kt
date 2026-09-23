package com.singularity.todo.core.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * JVM/Linux implementation of [NotificationPort].
 *
 * - Immediate notifications: `notify-send` (libnotify)
 * - Scheduled notifications: `at` daemon (reads from stdin)
 *
 * Availability is determined by checking for `notify-send` on PATH.
 */
class JvmNotificationPort : NotificationPort {

    override val isAvailable: Boolean
        get() = runCatching {
            ProcessBuilder("which", "notify-send")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)

    override suspend fun scheduleAt(
        key: String,
        title: String,
        body: String,
        fireAtEpochMs: Long,
        payload: String?,
        viewId: String?,
    ) = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext

        val instant = Instant.ofEpochMilli(fireAtEpochMs)
        val local = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        val atTime = "%02d:%02d %02d/%02d/%04d".format(
            local.hour,
            local.minute,
            local.dayOfMonth,
            local.monthValue,
            local.year,
        )

        // Build the notify-send command that at(1) will invoke
        val notifyCmd = listOf(
            "notify-send",
            "--urgency=normal",
            "--app-name=Singularity",
            "--icon=dialog-information",
            title,
            body,
        )

        // at(1) reads the command from stdin; schedule it
        val atJob = ProcessBuilder("at", atTime)
            .redirectErrorStream(true)
            .start()

        atJob.outputStream.bufferedWriter().use { w ->
            w.write(notifyCmd.joinToString(" ") { shquote(it) })
            w.newLine()
        }

        val exit = atJob.waitFor()
        if (exit != 0) {
            // Fallback: fire immediately if at daemon isn't running
            fireNow(title, body)
        }
    }

    override suspend fun cancel(key: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val jobs = readJobFile()
                jobs[key]?.let { jobId ->
                    ProcessBuilder("atrm", jobId).start().waitFor()
                }
                writeJobFile(jobs.filterKeys { it != key })
            }
        }
    }

    override suspend fun cancelAll() {
        withContext(Dispatchers.IO) {
            if (!isAvailable) return@withContext
            runCatching {
                ProcessBuilder("atq")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readLines()
                    .mapNotNull { line ->
                        line.takeWhile { it.isDigit() }.toIntOrNull()
                    }
                    .forEach { jobId ->
                        ProcessBuilder("atrm", jobId.toString()).start().waitFor()
                    }
                writeJobFile(emptyMap())
            }
        }
    }

    private fun fireNow(title: String, body: String) {
        if (!isAvailable) return
        ProcessBuilder(
            "notify-send",
            "--urgency=normal",
            "--app-name=Singularity",
            "--icon=dialog-information",
            title,
            body,
        ).start().waitFor()
    }

    // ─── Job file helpers ────────────────────────────────────────────────────

    private val jobFile = File("${System.getProperty("user.home")}/.singularity-todo/notify-jobs.txt")

    private fun readJobFile(): Map<String, String> {
        if (!jobFile.exists()) return emptyMap()
        return jobFile.readLines().associate { line ->
            val parts = line.split(':', limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
    }

    private fun writeJobFile(jobs: Map<String, String>) {
        jobFile.parentFile?.mkdirs()
        jobFile.writeText(jobs.entries.joinToString("\n") { "${it.key}:${it.value}" })
    }

    // Minimal shell quoting — escapes a single word for sh(1)
    private fun shquote(s: String): String {
        if (s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '/' }) return s
        return "'${s.replace("'", "'\\''")}'"
    }
}
