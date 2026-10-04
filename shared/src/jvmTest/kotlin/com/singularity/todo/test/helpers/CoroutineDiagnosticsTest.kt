package com.singularity.todo.test.helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import kotlin.test.assertContains

/**
 * Smoke tests for [CoroutineDiagnostics] in shared/jvmTest.
 * Mirrors [com.singularity.todo.test.helpers.CoroutineDiagnosticsTest] in desktopApp
 * — both copies are tested independently since there is no shared test-fixtures module.
 *
 * These do NOT test the output format — format is verified by reading actual
 * dumps during real incidents. These only confirm the agent is working.
 */
@Tag("fast")
class CoroutineDiagnosticsTest {

    @Test
    fun `dump sees a suspended coroutine`() = runTest {
        val job = CoroutineScope(coroutineContext).launch {
            delay(Long.MAX_VALUE)
        }
        try {
            val dump = CoroutineDiagnostics.dump("CoroutineDiagnosticsTest")
            assertContains(dump, "SUSPENDED")
            assertContains(dump, "CoroutineDiagnosticsTest")
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `dump captures infinite flow coroutine`() = runTest {
        val infiniteFlow = flow {
            while (true) {
                emit(42)
                // Virtual time: this runs under runTest's TestCoroutineScheduler, so the
                // delay parks the coroutine without costing a real second. The point of
                // the test is to have a SUSPENDED coroutine visible in the dump.
                @Suppress("NoRealDelayInTest")
                delay(1000L)
            }
        }
        val job = CoroutineScope(coroutineContext).launch {
            infiniteFlow.collect { }
        }
        try {
            val dump = CoroutineDiagnostics.dump("CoroutineDiagnosticsTest")
            assertContains(dump, "SUSPENDED")
        } finally {
            job.cancel()
        }
    }
}
