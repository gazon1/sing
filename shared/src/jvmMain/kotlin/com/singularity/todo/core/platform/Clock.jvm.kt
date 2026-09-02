package com.singularity.todo.core.platform

actual object Clock {
    actual fun now(): kotlinx.datetime.Instant {
        val systemMillis = kotlin.time.Clock.System.now().toEpochMilliseconds()
        return kotlinx.datetime.Instant.fromEpochMilliseconds(systemMillis)
    }
}
