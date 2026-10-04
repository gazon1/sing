package com.singularity.todo.core.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The behavioural difference the 199-site migration bought.
 *
 * `runCatching` catches `Throwable`, so in a suspend function it converts a
 * `CancellationException` into `Result.failure`. The coroutine then looks like it
 * finished normally to everything upstream and the job is never cancelled — the
 * "coroutine died silently before first emission" failure that
 * `2026-10-03-kotlinx-coroutines-debug` exists to diagnose.
 *
 * These tests existed as a helper's unit contract before the migration; they are
 * pinned here because the migration made them load-bearing for ~200 call sites.
 */
class RunCatchingCancellableTest {

    @Test
    fun `success is returned as-is`() = runTest {
        val result = runCatchingCancellable { 42 }
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `ordinary exception becomes a failure`() {
        val result = runCatchingCancellable { throw IllegalStateException("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `CancellationException propagates instead of becoming a failure`() {
        // The whole point. runCatching would return Result.failure here and the
        // cancellation would be lost.
        assertFailsWith<CancellationException> {
            runCatchingCancellable { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `kotlin runCatching is the behaviour being replaced`() {
        // Pinned so the difference stays visible: this is what the 199 sites did
        // before, and it is the bug.
        val result = runCatching { throw CancellationException("cancelled") }
        assertTrue(
            result.isFailure,
            "runCatching swallows CancellationException — this is why the migration exists",
        )
    }

    @Test
    fun `Error propagates and is not captured`() {
        // OutOfMemoryError must never become a Result.failure; a caller would
        // treat a dead process as a handled error.
        assertFailsWith<StackOverflowError> {
            runCatchingCancellable { throw StackOverflowError() }
        }
    }
}
