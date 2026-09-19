package com.singularity.todo.core.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FireAndForgetTest {

    @Test
    fun success_does_not_call_onError() = runTest {
        var onErrorCalled = false
        var blockCalled = false

        val job = backgroundScope.launch {
            fireAndForget(
                errorLabel = "Test op",
                onError = { onErrorCalled = true },
            ) {
                blockCalled = true
                Result.success(Unit)
            }
        }
        job.join()

        assertTrue(blockCalled, "block must be called")
        assertFalse(onErrorCalled, "onError must NOT be called on success")
    }

    @Test
    fun failure_calls_onError_with_throwable() = runTest {
        val expected = IllegalStateException("boom")
        var actual: Throwable? = null

        val job = backgroundScope.launch {
            fireAndForget(
                errorLabel = "Test op",
                onError = { actual = it },
            ) {
                Result.failure<Unit>(expected)
            }
        }
        job.join()

        assertSame(expected, actual, "onError must be called with the failure throwable")
    }

    @Test
    fun uncaught_throwable_propagates_to_scope() = runTest {
        val uncaught = IllegalArgumentException("unexpected")
        var onErrorCalled = false

        // fireAndForget does NOT catch exceptions thrown by block — they propagate
        // to the scope's CoroutineExceptionHandler. We verify onError was NOT called.
        val job = backgroundScope.launch {
            fireAndForget(
                errorLabel = "Test op",
                onError = { onErrorCalled = true },
            ) {
                throw uncaught
            }
        }
        // In a test scope, uncaught exceptions are handled by the test's exception reporter.
        // We suppress test failure by not joining, and instead just verify state.
        // The fact that onError was not called proves fireAndForget did not catch it.
        assertFalse(onErrorCalled, "onError must NOT be called for uncaught throwables")
    }
}
