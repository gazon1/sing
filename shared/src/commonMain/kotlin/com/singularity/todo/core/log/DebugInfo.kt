package com.singularity.todo.core.log

import kotlinx.datetime.TimeZone

private const val TAG = "Startup"

/**
 * Logs application startup information: version, build type, OS description.
 * Call this once at app startup, after [initLogging].
 *
 * @param version Human-readable version string (e.g. `"0.1.0"`).
 * @param isDebug `true` if this is a debug build.
 */
fun logStartup(version: String, isDebug: Boolean) {
    val buildType = if (isDebug) "debug" else "release"
    co.touchlab.kermit.Logger.i(TAG) { "Singularity v$version ($buildType) on ${osDescription()}" }
}

/**
 * Returns a multi-line debug info string containing startup details, locale, and timezone.
 * Suitable for inclusion in bug reports.
 *
 * @param version Human-readable version string.
 * @param isDebug `true` if this is a debug build.
 */
fun debugInfo(version: String, isDebug: Boolean): String {
    val buildType = if (isDebug) "debug" else "release"
    val tz = TimeZone.currentSystemDefault()
    return buildString {
        appendLine("App:        Singularity v$version ($buildType)")
        appendLine("OS:         ${osDescription()}")
        appendLine("Locale:     ${kotlinx.datetime.TimeZone.currentSystemDefault().id}")
        appendLine("Timezone:   ${tz.id}")
    }
}
