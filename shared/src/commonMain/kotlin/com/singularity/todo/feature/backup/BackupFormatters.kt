package com.singularity.todo.feature.backup

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Pure formatting helpers for backup UI.
 * Moved out of [BackupScreen] so they can be unit-tested without Compose
 * and used in JVM-only environments without [java.text.SimpleDateFormat].
 */

/**
 * Delegates to the shared formatter. The local copy stopped at megabytes, so a
 * 2 GB backup rendered as "2048.0 MB"; [formatFileSize] in `core.ui` handles GB.
 */

/** Formats an epoch-milliseconds instant into a local date-time string. */
internal fun formatBackupDate(epochMillis: Long, zone: TimeZone): String {
    val ldt = kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    return "%04d-%02d-%02d %02d:%02d".format(
        ldt.year,
        ldt.month.number,
        ldt.day,
        ldt.hour,
        ldt.minute,
    )
}
