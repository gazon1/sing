package com.singularity.todo.core.log

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

/**
 * Kermit initialization — must be called BEFORE [org.koin.core.context.startKoin].
 *
 * Platform-specific details:
 * - **JVM**: replaces the default writer with [ColorizedWriter] (ANSI colors, OS-aware),
 *   then wraps it with [FileLogWriter] for persistent rolling logs.
 * - **Android**: leaves [co.touchlab.kermit.platformLogWriter] as-is (Logcat handles colors),
 *   then adds [FileLogWriter] for persistent rolling logs.
 *
 * Severity filtering is global: [Severity.Verbose] in debug, [Severity.Warn] in release.
 * Messages with lower severity never construct their lazy string when filtered.
 *
 * @param isDebug `true` for debug builds (verbose logging).
 * @param version Human-readable version string shown in [logStartup].
 */
expect fun initLogging(isDebug: Boolean, version: String)

/** Internal helper — applies severity to the global [Logger]. */
internal fun applyGlobalSeverity(isDebug: Boolean) {
    Logger.setMinSeverity(if (isDebug) Severity.Verbose else Severity.Warn)
}
