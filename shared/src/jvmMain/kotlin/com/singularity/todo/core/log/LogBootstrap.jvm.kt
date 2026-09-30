package com.singularity.todo.core.log

import co.touchlab.kermit.Logger
import okio.Path

/**
 * JVM entry point — writes to both a colorized console ([ColorizedWriter]) and
 * a rolling file under [logDirectory]. Both writers are wrapped in [RedactingLogWriter]
 * so credential-shaped substrings are redacted before reaching any sink.
 *
 * A shutdown hook is registered that calls [FileLogWriter.beginShutdown] to flush
 * buffered entries before the process exits.
 */
actual fun initLogging(isDebug: Boolean, version: String, logDirectory: Path) {
    applyGlobalSeverity(isDebug)
    val fileWriter = FileLogWriter(logDirectory)
    // Fan out to both the colorized console and the file, with redaction on both.
    Logger.setLogWriters(
        RedactingLogWriter(ColorizedWriter()),
        RedactingLogWriter(fileWriter),
    )
    logStartup(version, isDebug)

    // Register shutdown hook to flush log buffers before exit.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            fileWriter.beginShutdown()
        },
    )
}
