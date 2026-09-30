package com.singularity.todo.test.helpers

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [LogWriter] that stores the last [capacity] log entries in a lock-free ring.
 *
 * Used as a per-test diagnostic buffer: the harness installs one into the global
 * Kermit logger before each test and drains it into a failure report if the test
 * fails. The ring discards the oldest entries once [capacity] is reached, which
 * keeps memory bounded even for very verbose test runs.
 *
 * @param capacity Maximum entries to retain. Defaults to 2 000 — a long Compose
 *                 boot with debug tracing is well under that, while a few
 *                 minutes of runaway logging stays bounded.
 */
class RingBufferLogWriter(
    private val capacity: Int = 2_000,
) : LogWriter() {

    private val buffer = CopyOnWriteArrayList<Entry>()
    private val lostCount = AtomicInteger()

    data class Entry(
        val timestamp: String,
        val severity: Severity,
        val tag: String,
        val message: String,
        val throwable: String?,
    ) {
        override fun toString(): String = "[$timestamp] ${severity.name} $tag: $message${throwable?.let { "\n$it" } ?: ""}"
    }

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
        val throwableText = throwable?.let {
            buildString {
                append(it::class.java.name)
                it.message?.let { msg -> append(": ").append(msg) }
                append("\n")
                it.stackTraceToString()
            }
        }
        val entry = Entry(stamp, severity, tag, message, throwableText)
        if (buffer.size >= capacity) {
            buffer.removeAt(0)
            lostCount.incrementAndGet()
        }
        buffer.add(entry)
    }

    /**
     * Returns all buffered entries as formatted lines, oldest first.
     *
     * A `"… X entries lost …"` marker is appended if the ring overflowed at least
     * once during the test.
     */
    fun drain(): String {
        val lines = buffer.map { it.toString() }.toList()
        val lost = lostCount.get()
        return if (lost > 0) {
            lines + "\n[Log ring buffer overflow: $lost entries discarded]"
        } else {
            lines
        }.joinToString("\n")
    }

    /** Resets the buffer so the same instance is safe to reuse across retry attempts. */
    fun reset() {
        buffer.clear()
        lostCount.set(0)
    }
}
