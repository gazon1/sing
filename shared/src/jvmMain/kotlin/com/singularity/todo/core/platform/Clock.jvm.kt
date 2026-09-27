package com.singularity.todo.core.platform

import kotlinx.datetime.TimeZone

actual val systemTimeZone: TimeZoneProvider = object : TimeZoneProvider {
    override fun current(): TimeZone = TimeZone.currentSystemDefault()
}
