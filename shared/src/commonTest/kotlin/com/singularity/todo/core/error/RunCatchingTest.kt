package com.singularity.todo.core.error

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RunCatchingTest {

    @Test
    fun success_returns_result_with_value() {
        val result = runCatchingCancellable { 42 }
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == 42)
    }

    @Test
    fun illegal_state_exception_returns_failure() {
        val result = runCatchingCancellable {
            error("boom")
        }
        assertTrue(result.isFailure)
        assertIs<IllegalStateException>(result.exceptionOrNull())
    }

    @Test
    fun cancellation_exception_propagates_not_caught() {
        val ex = assertFailsWith(CancellationException::class) {
            runCatchingCancellable {
                throw CancellationException("cancelled")
            }
        }
        assertTrue(ex.message == "cancelled")
    }

    @Test
    fun error_propagates_not_swallowed() {
        assertFailsWith<AssertionError> {
            runCatchingCancellable {
                assertTrue(false, "this should fail")
            }
        }
    }
}
