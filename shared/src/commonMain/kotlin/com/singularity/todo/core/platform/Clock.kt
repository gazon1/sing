package com.singularity.todo.core.platform


expect object Clock {
    fun now(): kotlin.time.Instant
}
