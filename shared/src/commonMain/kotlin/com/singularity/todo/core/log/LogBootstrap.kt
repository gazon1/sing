package com.singularity.todo.core.log

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

/**
 * Kermit initialization — must be called BEFORE [org.koin.core.context.startKoin].
 *
 * Platform-specific details:
 * - **JVM**: replaces the default writer with [ColorizedWriter] (ANSI colors, OS-aware).
 * - **Android**: leaves [co.touchlab.kermit.platformLogWriter] as-is (Logcat handles colors).
 *
 * Severity filtering is global: [Severity.Verbose] in debug, [Severity.Warn] in
 * release. Messages with lower severity never construct their lazy string when
 * filtered. Because the filter is global rather than per-writer, lowering it for
 * one sink lowers it for all of them.
 *
 * **Nothing is written to disk.** [FileLogWriter] and [LogExporter] are fully
 * implemented and [FileLogWriter] is covered by [FileLogWriterTest], but neither
 * is installed here and neither has a call site, so a release build keeps its
 * logs only in the platform sink. This paragraph used to claim the opposite;
 * see `deferred-backlog.md#file-log-writer-and-log-exporter-are-never-installed`
 * for whether wiring them is a product decision or they should be deleted.
 *
 * @param isDebug `true` for debug builds (verbose logging).
 * @param version Human-readable version string shown in [logStartup].
 */
expect fun initLogging(isDebug: Boolean, version: String)

/** Internal helper — applies severity to the global [Logger]. */
internal fun applyGlobalSeverity(isDebug: Boolean) {
    Logger.setMinSeverity(if (isDebug) Severity.Verbose else Severity.Warn)
}
