package com.singularity.todo.core.log

import co.touchlab.kermit.Logger
import co.touchlab.kermit.platformLogWriter
import okio.Path

/**
 * Android entry point — writes to both Logcat ([platformLogWriter]) and a rolling
 * file under [logDirectory]. Both writers are wrapped in [RedactingLogWriter] so
 * credential-shaped substrings are redacted before reaching any sink.
 *
 * Note: [Application.onTerminate] never fires on Android devices — the OS kills the
 * process without notice. There is no graceful shutdown on Android; logs are flushed
 * by the OS when the process is terminated.
 */
actual fun initLogging(isDebug: Boolean, version: String, logDirectory: Path) {
    applyGlobalSeverity(isDebug)
    val fileWriter = FileLogWriter(logDirectory)
    // Fan out to both Logcat and the file, with redaction on both sinks.
    Logger.setLogWriters(
        RedactingLogWriter(platformLogWriter()),
        RedactingLogWriter(fileWriter),
    )
    registerFileLogWriter(fileWriter)
    logStartup(version, isDebug)
}
