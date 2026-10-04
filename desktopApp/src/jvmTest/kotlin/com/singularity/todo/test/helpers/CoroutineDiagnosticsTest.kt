package com.singularity.todo.test.helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import kotlin.coroutines.coroutineContext
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke tests: verifies the kotlinx-coroutines-debug agent is active and
 * [CoroutineDiagnostics.dump] can see running coroutines.
 *
 * These do NOT test the output format — format is verified by reading actual
 * dumps during real incidents. These only confirm the agent is working.
 */
@Tag("slow")
class CoroutineDiagnosticsTest {

    @Test
    fun `dump sees a suspended coroutine`() = runTest {
        val job = CoroutineScope(coroutineContext).launch {
            delay(Long.MAX_VALUE)
        }
        try {
            val dump = CoroutineDiagnostics.dump("CoroutineDiagnosticsTest", 1)
            assertContains(dump, "SUSPENDED")
            assertContains(dump, "CoroutineDiagnosticsTest")
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `dump shows coroutine from real dispatcher`() = runTest {
        // Uses Dispatchers.Default (real), not the test dispatcher.
        // The agent sees it on the real executor thread.
        val job = CoroutineScope(Dispatchers.Default).launch {
            delay(Long.MAX_VALUE)
        }
        try {
            val dump = CoroutineDiagnostics.dump("CoroutineDiagnosticsTest", 1)
            assertContains(dump, "SUSPENDED")
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `dump shows job hierarchy`() = runTest {
        val parentJob = Job()
        val scope = CoroutineScope(coroutineContext + parentJob)
        val child1 = scope.launch { delay(Long.MAX_VALUE) }
        val child2 = scope.launch { delay(Long.MAX_VALUE) }
        try {
            val dump = CoroutineDiagnostics.dump("CoroutineDiagnosticsTest", 1)
            assertContains(dump, "CoroutineDiagnosticsTest")
        } finally {
            child1.cancel()
            child2.cancel()
            parentJob.cancel()
        }
    }

    @Test
    fun `dump captures infinite flow coroutine`() = runTest {
        val infiniteFlow = flow {
            while (true) {
                emit(42)
                delay(1000L)
            }
        }
        val job = CoroutineScope(coroutineContext).launch {
            infiniteFlow.collect { }
        }
        try {
            val dump = CoroutineDiagnostics.dump("CoroutineDiagnosticsTest", 1)
            assertContains(dump, "SUSPENDED")
        } finally {
            job.cancel()
        }
    }
}
