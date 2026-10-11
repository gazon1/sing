package com.singularity.todo.core.platform

import kotlin.time.Duration.Companion.milliseconds

/**
 * Named time constants to replace magic numbers throughout the codebase.
 *
 * Use these instead of inline literals like `86_400_000L` or `5_000.milliseconds`.
 */
object TimeConstants {
    /** Milliseconds per second. */
    const val MILLIS_PER_SECOND = 1_000L

    /** Milliseconds per standard day (24 × 60 × 60 × 1000). */
    const val MILLIS_PER_DAY = 86_400_000L

    /** Milliseconds per standard hour (1 × 60 × 60 × 1000). */
    const val MILLIS_PER_HOUR = 3_600_000L

    /** Seconds per standard day (24 × 60 × 60). */
    const val SECONDS_PER_DAY = 86_400

    /** Auto-save debounce interval. */
    val AutoSaveDebounceMs = 1_500.milliseconds

    /** One-second duration for timers and polling. */
    val OneSecond = 1_000.milliseconds

    /** Backup refresh interval. */
    val BackupRefreshIntervalMs = 5_000.milliseconds
}
