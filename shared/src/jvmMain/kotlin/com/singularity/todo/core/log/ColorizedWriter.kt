package com.singularity.todo.core.log

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.MessageStringFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Tag
import co.touchlab.kermit.platformLogWriter

/**
 * JVM-only [LogWriter] that wraps [platformLogWriter] and adds ANSI color
 * codes based on log severity.
 *
 * - `Verbose` / `Debug` → gray
 * - `Info`            → green
 * - `Warn`            → yellow
 * - `Error` / `Assert` → red
 *
 * Respects the `NO_COLOR` environment variable and is OS-aware:
 * enabled on Linux, macOS, and Windows 10+; disabled in dumb terminals.
 */
class ColorizedWriter : LogWriter() {
    private val delegate: LogWriter = platformLogWriter(ColorizedFormatter)
    private val ansiEnabled: Boolean = detectAnsi()

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        val colored = if (ansiEnabled) decorate(severity, message) else message
        delegate.log(severity, colored, tag, throwable)
    }

    private fun decorate(
        severity: Severity,
        message: String,
    ): String {
        val color =
            when (severity) {
                Severity.Verbose, Severity.Debug -> GRAY
                Severity.Info -> GREEN
                Severity.Warn -> YELLOW
                Severity.Error, Severity.Assert -> RED
            }
        return "$color$message$RESET"
    }

    private fun detectAnsi(): Boolean {
        if (System.getenv("NO_COLOR") != null) return false
        val os = System.getProperty("os.name", "").lowercase()
        return os.contains("linux") || os.contains("mac") || os.contains("windows 10")
    }

    private object ColorizedFormatter : MessageStringFormatter {
        override fun formatSeverity(severity: Severity): String = "$severity:"

        override fun formatTag(tag: Tag): String = if (tag.tag.isEmpty()) "" else "(${tag.tag})"
    }

    private companion object {
        private const val RESET = "\u001B[0m"
        private const val GRAY = "\u001B[90m"
        private const val GREEN = "\u001B[32m"
        private const val YELLOW = "\u001B[33m"
        private const val RED = "\u001B[31m"
    }
}
