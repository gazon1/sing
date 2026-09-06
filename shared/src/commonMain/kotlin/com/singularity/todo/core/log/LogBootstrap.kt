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
 * Severity filtering is global: [Severity.Verbose] in debug, [Severity.Warn] in release.
 * Messages with lower severity never construct their lazy string when filtered.
 */
expect fun initLogging(isDebug: Boolean, version: String)

/** Internal helper — applies severity to the global [Logger]. */
internal fun applyGlobalSeverity(isDebug: Boolean) {
    Logger.setMinSeverity(if (isDebug) Severity.Verbose else Severity.Warn)
}
