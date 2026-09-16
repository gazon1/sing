package com.singularity.todo.core.platform

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object Clock {
    fun now(): kotlin.time.Instant
}

expect fun todayInSystemZone(): kotlinx.datetime.LocalDate

expect val isDesktop: Boolean
