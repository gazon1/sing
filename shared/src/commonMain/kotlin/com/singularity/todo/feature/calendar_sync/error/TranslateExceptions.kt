package com.singularity.todo.feature.calendar_sync.error

/**
 * Wraps Android-specific exception types thrown by [android.content.ContentResolver]
 * into typed [CalendarSyncException] subclasses.
 *
 * The Android platform actual is in the `androidMain` source set.
 * The JVM actual is a no-op passthrough (calendar sync is Android-only).
 */
expect inline fun <T> translateExceptions(block: () -> T): T
