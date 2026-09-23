package com.singularity.todo.core.log

import co.touchlab.kermit.Logger

/**
 * JVM init: adds [FileLogWriter] (persistent rolling logs) before [ColorizedWriter]
 * (ANSI-colored console output).
 *
 * @param isDebug `true` enables [co.touchlab.kermit.Severity.Verbose] logging.
 * @param version Human-readable version shown in [logStartup].
 */
actual fun initLogging(isDebug: Boolean, version: String) {
    applyGlobalSeverity(isDebug)

    val fileWriter = FileLogWriter(logDirectory())
    // Register FileLogWriter first, ColorizedWriter second.
    // Both receive all log entries. ColorizedWriter writes to stdout.
    Logger.setLogWriters(fileWriter, ColorizedWriter())

    logStartup(version, isDebug)
}
