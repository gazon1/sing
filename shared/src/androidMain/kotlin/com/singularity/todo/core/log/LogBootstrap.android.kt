package com.singularity.todo.core.log

import co.touchlab.kermit.Logger

/**
 * Android entry point — Logcat handles colors and formatting natively via
 * [co.touchlab.kermit.platformLogWriter], so no writer replacement is needed.
 */
actual fun initLogging(isDebug: Boolean, version: String) {
    applyGlobalSeverity(isDebug)
    Logger.i { "Singularity Todo $version started, isDebug=$isDebug" }
}
