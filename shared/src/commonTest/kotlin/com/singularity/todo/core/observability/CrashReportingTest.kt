@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.observability

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.backgroundFailureHandler
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.coroutines.EmptyCoroutineContext

/** Records what the funnel reported, so tests can assert on it without a mocking framework. */
private class RecordingCrashReporter : CrashReportingPort {
    val reports = mutableListOf<Pair<Throwable, String>>()
    val breadcrumbs = mutableListOf<String>()

    override fun report(error: Throwable, issueKey: String) {
        reports += error to issueKey
    }

    override fun addBreadcrumb(message: String) {
        breadcrumbs += message
    }
}

private sealed class S {
    data object Idle : S()
    data class Err(val message: String) : S()
}

private sealed interface I : MviIntent {
    data object ReturnedFailure : I
    data object ThrownPlain : I
    data object ReturnedAppError : I
    data object Succeed : I
}

private interface E : MviEvent

private class Vm(scope: CoroutineScope, reporter: CrashReportingPort, private val block: suspend () -> Result<*>) :
    MviViewModel<S, I, E>(
        initialState = S.Idle,
        crashReporter = reporter,
        scope = AutoCloseableCoroutineScope(scope.coroutineContext),
    ) {
    override fun onIntent(intent: I) {
        drive(intent)
    }

    fun drive(intent: I) = when (intent) {
        I.ReturnedFailure -> catchTo("vm.op_failed", { msg -> updateState { S.Err(msg) } }) { block() }
        I.ThrownPlain -> catchTo("vm.op_threw", { msg -> updateState { S.Err(msg) } }) { block() }
        I.ReturnedAppError -> catchTo("vm.not_found", { msg -> updateState { S.Err(msg) } }) { block() }
        I.Succeed -> catchTo("vm.ok", { msg -> updateState { S.Err(msg) } }) { block() }
    }
}

/**
 * The MVI error path is the single seam where handled failures become visible, so that is
 * the only place the reporter is asserted — see the ADR's testing decisions.
 */
@Tag("fast")
class MviViewModelCrashReportingTest {

    @Test
    fun `a returned failure is reported once, grouped by the call-site label`() = runTest {
        val reporter = RecordingCrashReporter()
        val boom = IllegalStateException("disk gone")
        val vm = Vm(this, reporter) { Result.failure<Unit>(boom) }

        vm.drive(I.ReturnedFailure)
        advanceUntilIdle()

        assertEquals(1, reporter.reports.size, "Exactly one report per failure")
        assertSame(boom, reporter.reports.single().first, "The ORIGINAL throwable, not a copy")
        assertEquals("vm.op_failed", reporter.reports.single().second)
    }

    @Test
    fun `a thrown non-AppError is reported under the call-site label`() = runTest {
        val reporter = RecordingCrashReporter()
        val boom = IllegalArgumentException("bad id")
        // Declared with its type so the lambda body (which only throws) has an
        // expected type; an inline trailing lambda infers T from a Nothing body.
        val throwsInstead: suspend () -> Result<*> = { throw boom }
        val vm = Vm(this, reporter, throwsInstead)

        vm.drive(I.ThrownPlain)
        advanceUntilIdle()

        assertEquals(1, reporter.reports.size)
        assertSame(boom, reporter.reports.single().first)
        assertEquals("vm.op_threw", reporter.reports.single().second)
    }

    @Test
    fun `an AppError is grouped by its domain code, not the call-site label`() = runTest {
        val reporter = RecordingCrashReporter()
        val vm = Vm(this, reporter) { Result.failure<Unit>(AppError.NotFound("Task not found: abc")) }

        vm.drive(I.ReturnedAppError)
        advanceUntilIdle()

        assertEquals(1, reporter.reports.size)
        assertEquals("error.not_found", reporter.reports.single().second)
    }

    @Test
    fun `success reports nothing`() = runTest {
        val reporter = RecordingCrashReporter()
        val vm = Vm(this, reporter) { Result.success(Unit) }

        vm.drive(I.Succeed)
        advanceUntilIdle()

        assertTrue(reporter.reports.isEmpty(), "A successful operation is not an error")
    }

    @Test
    fun `the issue key never carries the error message`() = runTest {
        val reporter = RecordingCrashReporter()
        val vm = Vm(this, reporter) {
            Result.failure<Unit>(IllegalStateException("john@example.com bought milk"))
        }

        vm.drive(I.ReturnedFailure)
        advanceUntilIdle()

        // The key is what leaves the device. It is a label or a code, never the message.
        assertTrue(
            !reporter.reports.single().second.contains("@"),
            "Issue key must not embed user content: ${reporter.reports.single().second}",
        )
    }
}

/** The no-op must be genuinely inert — it is the default for every ViewModel test. */
@Tag("fast")
class NoOpCrashReportingPortTest {

    @Test
    fun `report and addBreadcrumb are silent no-ops`() {
        val port = NoOpCrashReportingPort()
        port.report(IllegalStateException("boom"), "some.issue_key")
        port.addBreadcrumb("something happened")
    }
}

/**
 * Cause preservation is the half of the AppError change that fixes a real defect:
 * `runCatchingResult` used to flatten every throwable to its message, discarding the
 * stack trace at exactly the point where it would be read.
 */
@Tag("fast")
class AppErrorCauseTest {

    @Test
    fun `runCatchingResult keeps the original throwable as the cause`() {
        val original = IOException("socket closed")

        val result = runCatchingResult<Unit> { throw original }

        val error = result.exceptionOrNull() as AppError.Unknown
        assertSame(original, error.cause, "The cause chain must survive")
        assertEquals("socket closed", error.message)
        assertEquals("error.unknown", error.code)
    }

    @Test
    fun `an AppError passes through untouched`() {
        val original = AppError.NotFound("Tag gone")

        val result = runCatchingResult<Unit> { throw original }

        assertSame(original, result.exceptionOrNull())
    }
}

/** Every subtype must still construct from a bare message — the trailing params are defaulted. */
@Tag("fast")
class AppErrorCompatibilityTest {

    @Test
    fun `each subtype constructs from a message alone and has a stable code`() {
        val pairs: List<Pair<AppError, String>> = listOf(
            AppError.Validation("v") to "error.validation",
            AppError.NotFound("n") to "error.not_found",
            AppError.Unauthorized("u") to "error.unauthorized",
            AppError.Persistence("p") to "error.persistence",
            AppError.Network("net") to "error.network",
            AppError.Unknown("x") to "error.unknown",
        )
        for ((error, expectedCode) in pairs) {
            assertEquals(expectedCode, error.code, "code drift in ${error::class.simpleName}")
        }
    }

    @Test
    fun `an explicit code overrides the default`() {
        assertEquals("sync.push_failed", AppError.Network("timeout", code = "sync.push_failed").code)
    }

    @Test
    fun `a cause supplied to a subtype is retained`() {
        val cause = IOException("root")
        assertSame(cause, AppError.Persistence("write failed", cause = cause).cause)
    }
}

/**
 * The scope factory is the bottom of the error funnel, so what a scope is composed *with* is
 * the load-bearing invariant here: without a [CoroutineExceptionHandler] a throwing `launch`
 * escalates to the platform's uncaught-exception handler and, on Android, kills the process.
 * That escalation is invisible in a test run, so it is asserted on the context instead.
 *
 * ## This class no longer needs to be isolated, and that is the point
 *
 * It used to install into a process-wide `BackgroundFailureHandler` and therefore required
 * `@Execution(SAME_THREAD)`, plus `forkEvery = 1` so no other class could receive a failure
 * its installed target captured. Two preconditions for one test's correctness, neither
 * visible at the call site. The handler is a value now, so a test builds its own and cannot
 * be perturbed by anything running beside it. `ForkEveryIsolationTest` keeps asserting the
 * `forkEvery` setting for its own sake, but nothing about the failure handler depends on it.
 */
@Tag("fast")
class BackgroundFailureHandlerTest {

    private companion object {
        /** Real threads are involved, so this is a hang guard, not a timing assertion. */
        const val TIMEOUT_MS = 5_000L
    }

    @Test
    fun `a scope built from a handler carries that handler`() {
        val handler = backgroundFailureHandler { }
        val scope = createBackgroundScope(handler)

        assertSame(
            handler,
            scope.coroutineContext[CoroutineExceptionHandler],
            "A scope without a handler escalates a failed launch to the platform's " +
                "uncaught-exception handler, which kills an Android process",
        )
    }

    @Test
    fun `a failed launch is reported and the scope survives it`() = runTest {
        val seen = CompletableDeferred<Throwable>()
        val scope = createBackgroundScope(backgroundFailureHandler { seen.complete(it) })

        // Real threads, so the waits below are real too. Inside withContext(Dispatchers.Default)
        // the test scheduler is out of the way, which means withTimeout is a genuine hang guard
        // rather than a virtual-time skip. runBlocking would say the same thing more directly,
        // but it is banned outside the baseline.
        withContext(Dispatchers.Default) {
            scope.launch { throw IllegalStateException("disk gone") }.join()

            assertEquals("disk gone", withTimeout(TIMEOUT_MS) { seen.await() }.message)
            // SupervisorJob plus a handler that returns normally: the scope is not dead.
            withTimeout(TIMEOUT_MS) { scope.launch { }.join() }
        }
    }

    @Test
    fun `two handlers are independent, so one failure cannot be captured by the other`() {
        // The property the global could not have. Two components disagreeing about failures is
        // the supported case; with a single process-wide target, a failure raised in one could
        // be reported to the other's handler depending purely on ordering.
        val mine = mutableListOf<Throwable>()
        val theirs = mutableListOf<Throwable>()

        val myScope = createBackgroundScope(backgroundFailureHandler { mine += it })
        val theirScope = createBackgroundScope(backgroundFailureHandler { theirs += it })
        val boom = IllegalStateException("mine")

        myScope.coroutineContext[CoroutineExceptionHandler]!!
            .handleException(EmptyCoroutineContext, boom)

        assertEquals(1, mine.size, "The failure reached its own handler")
        assertTrue(theirs.isEmpty(), "and nobody else's: ${theirs.map { it.message }}")
    }

    @Test
    fun `cancellation is never reported`() {
        val recorded = mutableListOf<Throwable>()
        val handler = backgroundFailureHandler { recorded += it }

        handler.handleException(EmptyCoroutineContext, CancellationException("scope closed"))

        assertTrue(recorded.isEmpty(), "A cancelled coroutine is not a defect: $recorded")
    }

    @Test
    fun `a handled failure is dropped rather than rethrown`() {
        // No assertion beyond "this returns": the handler runs on a coroutine that is already
        // failing, so escaping here would escalate a background defect into process death.
        backgroundFailureHandler { }
            .handleException(EmptyCoroutineContext, IllegalStateException("boom"))
    }

    @Test
    fun `a failure is reported through the crash port under one machine-shaped key`() {
        val port = RecordingCrashReporter()
        val handler = crashReportingFailureHandler(port)
        val boom = IllegalStateException("john@example.com bought milk")

        handler.handleException(EmptyCoroutineContext, boom)

        assertTrue(port.reports.any { it.first === boom }, "The original throwable, not a copy")
        assertTrue(
            port.reports.all { it.second == BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY },
            "One stable key for the whole class: ${port.reports.map { it.second }}",
        )
        assertTrue(
            port.reports.none { it.second.contains("@") },
            "The key leaves the device and must not embed user content",
        )
    }

    @Test
    fun `a viewmodel scope reports to that viewmodel's own reporter`() {
        // The migration's whole point, asserted: a ViewModel's unhandled background failure
        // goes to the port it was constructed with, with nothing installed anywhere.
        val port = RecordingCrashReporter()
        val scope = reportingScope(port)
        val boom = IllegalStateException("collector died")

        scope.coroutineContext[CoroutineExceptionHandler]!!.handleException(EmptyCoroutineContext, boom)

        assertEquals(1, port.reports.size)
        assertSame(boom, port.reports.single().first)
        assertEquals(BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY, port.reports.single().second)
    }
}
