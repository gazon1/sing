package com.singularity.todo.core.platform

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

expect object Clock {
    fun now(): Instant
}
