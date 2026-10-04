package com.singularity.todo.core.log

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import okio.Path

/**
 * Kermit initialization — must be called BEFORE [org.koin.core.context.startKoin].
 *
 * Installs [FileLogWriter] to disk on both Android and JVM, plus a console/writer
 * (colorized on JVM, platform log writer on Android). Both writers are registered
 * with [Logger.setLogWriters] so log calls fan out to both sinks.
 *
 * On JVM a shutdown hook is registered that calls [FileLogWriter.beginShutdown]
 * to flush the buffer before exit. On Android [Application.onTerminate] never
 * fires on real devices — logs are flushed only at process termination by the OS.
 *
 * Severity filtering is global: [Severity.Verbose] in debug, [Severity.Warn] in
 * release. Messages with lower severity never construct their lazy string when
 * filtered. Because the filter is global rather than per-writer, lowering it for
 * one sink lowers it for all of them.
 *
 * @param isDebug `true` for debug builds (verbose logging).
 * @param version Human-readable version string shown in [logStartup].
 * @param logDirectory Directory where rolling log files are written. Created if absent.
 */
expect fun initLogging(isDebug: Boolean, version: String, logDirectory: Path)

/**
 * The file writer installed by the most recent [initLogging], or `null` if logging was never
 * initialised. Held here rather than returned to the caller so the Kermit type stays inside
 * this module: `FileLogWriter` extends Kermit's `LogWriter`, and `androidApp` does not have
 * Kermit on its compile classpath, so naming the type there would not compile.
 */
private var activeFileLogWriter: FileLogWriter? = null

/** Called by each platform's [initLogging] once it has built its writer. */
internal fun registerFileLogWriter(writer: FileLogWriter) {
    activeFileLogWriter = writer
}

/**
 * Drains the rolling log file, so entries already handed to the writer reach disk.
 *
 * Android never calls `Application.onTerminate`, so nothing drains the writer when the OS
 * kills the process. An uncaught-exception handler calls this to recover the last few
 * lines — the operations immediately before the crash.
 *
 * Safe to call before [initLogging] and safe to call more than once; a no-op in both cases.
 */
fun flushLogs() {
    runCatching { activeFileLogWriter?.beginShutdown() }
}

/** Internal helper — applies severity to the global [Logger]. */
internal fun applyGlobalSeverity(isDebug: Boolean) {
    Logger.setMinSeverity(if (isDebug) Severity.Verbose else Severity.Warn)
}
