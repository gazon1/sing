package com.singularity.todo.core.platform

/**
 * Monotonic clock reading in milliseconds.
 *
 * Unlike [kotlin.time.Clock.System][Clock.System], this source is guaranteed to never
 * jump backwards or skip forward when the system wall clock is adjusted (e.g. NTP sync,
 * user change, device sleep). Used for retry backoff timers where a clock jump would
 * cause premature or indefinitely-delayed retries.
 *
 * Android: [android.os.SystemClock.elapsedRealtime][android.os.SystemClock.elapsedRealtime]
 * JVM:     [System.nanoTime][System.nanoTime] / 1_000
 */
expect val monoClockMillis: Long
