package com.singularity.todo.core.platform

import kotlinx.datetime.TimeZone

/**
 * Abstraction over [TimeZone.currentSystemDefault] so widget tests that need
 * a fixed zone can provide [FixedTimeZone] instead of touching the system clock.
 */
interface TimeZoneProvider {
    fun current(): TimeZone
}

/** Production provider — delegates to the platform's system zone. */
expect val systemTimeZone: TimeZoneProvider
