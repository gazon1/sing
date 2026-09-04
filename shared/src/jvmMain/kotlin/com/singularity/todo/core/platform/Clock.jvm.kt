package com.singularity.todo.core.platform

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

actual object Clock {
    actual fun now(): Instant {
        val systemMillis = kotlin.time.Clock.System.now().toEpochMilliseconds()
        return Instant.fromEpochMilliseconds(systemMillis)
    }
}

actual fun todayInSystemZone(): LocalDate {
    val nowMs = Clock.now().toEpochMilliseconds()
    val kxInstant = kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs)
    return kxInstant.toLocalDateTime(TimeZone.currentSystemDefault()).date
}
