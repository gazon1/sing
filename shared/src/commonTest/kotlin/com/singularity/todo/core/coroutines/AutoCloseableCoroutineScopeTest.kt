@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the close semantics of the injected lifecycle scope (#3228): closing is the
 * ViewModel-lifecycle teardown, so it has to mean exactly one thing — cancel the scope's
 * own job, and nothing else.
 */
@Tag("fast")
class AutoCloseableCoroutineScopeTest {
    @Test
    fun `close cancels the scope job`() =
        runTest {
            val scope = AutoCloseableCoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

            assertNotNull(scope.job, "a scope built from a context carrying a job has that job")
            assertFalse(scope.job.isCancelled)

            scope.close()

            assertTrue(scope.job.isCancelled, "close must cancel the scope's job")
        }

    @Test
    fun `close on a job-less context is a no-op`() {
        val scope = AutoCloseableCoroutineScope(EmptyCoroutineContext)

        // A scope with no job has no children to cancel, so closing it must not throw —
        // it is closed from `onCleared()`, a cleanup path where an exception is the worst
        // possible outcome.
        scope.close()

        assertFalse(scope.job?.isCancelled ?: false, "a job-less scope has nothing to cancel")
    }

    @Test
    fun `closing a scope does not cancel its parent scope`() =
        runTest {
            val parent = testScope(this)
            val child = testScope(parent)
            val childScopeJob = child.job ?: error("a child scope built from a job-carrying context has that job")
            var childWorkRan = false
            val launched = child.launch { childWorkRan = true }
            runCurrent()
            assertTrue(childWorkRan, "the child scope's work must run before the close")

            child.close()
            assertTrue(childScopeJob.isCancelled, "closing the child scope must cancel its own job")
            launched.join()

            var parentWorkRan = false
            val parentJob = parent.launch { parentWorkRan = true }
            runCurrent()
            assertTrue(parentWorkRan, "closing a child scope must not cancel the parent")
            parentJob.cancel()
            parent.job?.cancel()
        }
}
