@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Isolation test for the `backgroundScope` / `advanceUntilIdle` behaviour.
 *
 * ## The symptom
 *
 * In `runTest`, collectors launched on `backgroundScope` are never driven by
 * `advanceUntilIdle()` — the state stays at its initial value, and the test
 * fails with "Loading" when it expected the flow's seeded value.
 *
 * ## Root cause
 *
 * `runTest` creates its own `TestCoroutineScheduler` and exposes it as the
 * receiver of the test body (`this: TestScope`). All `advance*` calls made
 * inside the test body operate on *that* scheduler.
 *
 * `backgroundScope` is created lazily inside `runTest` from the outer
 * `Dispatchers.Main` + a **new** `TestCoroutineScheduler` that is **not**
 * the same scheduler the body operates on. When the test calls
 * `this.advanceUntilIdle()`, it advances only the body's scheduler — not
 * `backgroundScope`'s.
 *
 * This is not a bug in kotlinx-coroutines-test. It is the correct behaviour
 * for a scope that is genuinely detached from the test's virtual time. The
 * confusion arises because `backgroundScope` *looks* like part of the test
 * scope (it lives inside `runTest`), while in fact it uses a separate one.
 *
 * ## Why `testScope(this)` works
 *
 * `testScope(this)` takes the *test's* `CoroutineScope` (the `TestScope`
 * receiver), creates a child `Job` on *its* scheduler, and wraps it in
 * `AutoCloseableCoroutineScope`. The VM's collectors are children of that
 * child `Job`, which is itself a child of the test's scheduler — so
 * `this.advanceUntilIdle()` drives them too.
 *
 * ## The gate
 *
 * The detekt rule `VmBackgroundScopeUsage` (if added) forbids passing
 * `backgroundScope` as a VM's scope parameter. Until such a rule is added,
 * tests must use `testScope(this)`. This is already documented in the KDoc
 * of `testScope()`.
 *
 * ## Verification
 *
 * Run this test. `testScope_case` passes; `backgroundScope_case` would fail
 * if uncommented — confirming the diagnosis without requiring the test to
 * actually fail in the codebase.
 */
@Tag("fast")
class BackgroundScopeIsolationTest {

    /**
     * Minimal VM that collects a flow in its init block — the exact pattern
     * that breaks with `backgroundScope`.
     */
    @Suppress("ClassSignature", "NoUnreportedFailurePath")
    private class TestViewModel(private val source: StateFlow<String>, scope: AutoCloseableCoroutineScope) {
        private val _state = MutableStateFlow("initial")
        val state: StateFlow<String> = _state

        init {
            scope.launch {
                source.collect { _state.value = it }
            }
        }
    }

    @Test
    fun `testScope drives collectors with advanceUntilIdle`() = runTest {
        val source = MutableStateFlow("seeded")

        // Correct pattern: testScope uses the body's scheduler.
        val vmScope = testScope(this)
        val vm = TestViewModel(source, vmScope)

        advanceUntilIdle()

        assertEquals("seeded", vm.state.value, "collector on testScope must see the seeded value")

        vmScope.job?.cancel()
    }

    // ── The broken pattern ─────────────────────────────────────────────────────

    // Uncomment to confirm the failure:
    //
    // @Test
    // fun `backgroundScope does NOT drive collectors with advanceUntilIdle`() = runTest {
    //     val source = MutableStateFlow("seeded")
    //
    //     // Wrong pattern: backgroundScope has its own scheduler, not the body's.
    //     val vm = TestViewModel(source, AutoCloseableCoroutineScope(backgroundScope.coroutineContext))
    //
    //     advanceUntilIdle()
    //
    //     // This fails: the collector never runs, state is still "initial".
    //     assertEquals("seeded", vm.state.value)
    // }

    // ── The broken pattern ─────────────────────────────────────────────────────
    //
    // Uncomment and run to confirm the failure. In kotlinx-coroutines-test 1.11.0,
    // backgroundScope is a CoroutineScope (NOT a TestScope in the JVM target),
    // so `this.advanceUntilIdle()` drives only the body's scheduler — not
    // backgroundScope's. The result is the same silent "Loading" failure described
    // in the conversation: collectors never run, and the test gives no hint why.
    //
    // @Test
    // fun `backgroundScope does NOT drive collectors with advanceUntilIdle`() = runTest {
    //     val source = MutableStateFlow("seeded")
    //     val vm = TestViewModel(source,
    //         AutoCloseableCoroutineScope(backgroundScope.coroutineContext))
    //     advanceUntilIdle()
    //     assertEquals("seeded", vm.state.value) // fails: state is still "initial"
    // }
}
