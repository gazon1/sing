package com.singularity.todo.core.platform

import kotlin.time.Instant

actual object Clock {
    actual fun now(): Instant {
        val systemMillis = kotlin.time.Clock.System.now().toEpochMilliseconds()
        return Instant.fromEpochMilliseconds(systemMillis)
    }
}
