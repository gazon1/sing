package com.singularity.todo.feature.backup

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Pure formatting helpers for backup UI.
 * Moved out of [BackupScreen] so they can be unit-tested without Compose
 * and used in JVM-only environments without [java.text.SimpleDateFormat].
 */

/** Formats bytes into a human-readable string: "1.2 MB", "500 B", etc. */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
}

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
