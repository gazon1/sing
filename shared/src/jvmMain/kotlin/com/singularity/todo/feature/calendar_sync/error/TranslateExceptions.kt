package com.singularity.todo.feature.calendar_sync.error

/**
 * JVM stub for [translateExceptions].
 *
 * Calendar sync is Android-only; this is never called on JVM but satisfies
 * the expect/actual contract.
 */
actual inline fun <T> translateExceptions(block: () -> T): T = block()
