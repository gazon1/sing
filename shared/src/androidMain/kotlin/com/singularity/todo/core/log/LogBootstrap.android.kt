package com.singularity.todo.core.log

import co.touchlab.kermit.Logger
import co.touchlab.kermit.platformLogWriter

/**
 * Android init: adds [FileLogWriter] (persistent rolling logs) alongside the default
 * [platformLogWriter] (Logcat).
 *
 * @param isDebug `true` enables [co.touchlab.kermit.Severity.Verbose] logging.
 * @param version Human-readable version shown in [logStartup].
 */
actual fun initLogging(isDebug: Boolean, version: String) {
    applyGlobalSeverity(isDebug)

    // FileLogWriter writes to files in filesDir/logs.
    // platformLogWriter writes to Logcat. Both are active simultaneously.
    Logger.setLogWriters(FileLogWriter(logDirectory()), platformLogWriter())

    logStartup(version, isDebug)
}
