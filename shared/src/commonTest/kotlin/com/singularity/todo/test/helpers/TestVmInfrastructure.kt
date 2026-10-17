@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Context object for VM testing.
 *
 * Example — event-listening test (SavedAgenda pattern):
 * ```
 * val ctx = testVm(
 *     stateAccessor = { vm: SavedAgendaViewModel -> vm.state },
 * ) { testScope, bgScope ->
 *     val vm = SavedAgendaViewModel(deps = deps, scope = testScope)
 *     bgScope.launch { vm.events.collect { events += it } }
 *     vm
 * }
 * ctx.act { it.onIntent(SavedAgendaIntent.Delete) }
 * ctx.assert { events.any { it is SavedAgendaEvent.DeleteSuccess } }
 * ```
 *
 * @param vm The raw ViewModel under test.
 * @param state The VM's [StateFlow] — extracted once, cheaply.
 * @param scope The [TestScope] used to advance virtual time in [act].
 * @param backgroundScope A [CoroutineScope] that outlives the test body. Use for
 *     long-running collectors (e.g. `vm.events`) that must survive [act] calls.
 *     Coroutines on this scope share the test dispatcher but are not cancelled
 *     when the VM's [scope] is closed.
 */
class TestVmContext<VM, S>(
    val vm: VM,
    val state: StateFlow<S>,
    private val scope: TestScope,
    val backgroundScope: CoroutineScope,
) {
    /** Executes [action] on the VM and waits for pending work. */
    suspend fun act(action: VM.() -> Unit) {
        vm.action()
        scope.advanceUntilIdle()
        // advanceUntilIdle() returns when the scheduler is idle. runCurrent() flushes
        // any coroutines that were scheduled but not yet dispatched (e.g. catchTo's
        // vmScope.launch block — it is dispatched by the test scheduler but may not
        // have been run when advanceUntilIdle() returned).
        scope.runCurrent()
    }

    /** Asserts the current state is exactly of type [T] (subclass check). */
    inline fun <reified T : S> assertIs(): T = assertIs(state.value)

    /** Asserts [predicate] returns true for the current state. */
    fun assert(predicate: (S) -> Boolean) {
        assertTrue(predicate(state.value), "State ${state.value} does not satisfy predicate")
    }
}

/**
 * Creates a [TestVmContext] for the given [vm].
 *
 * @param vm The ViewModel under test.
 * @param stateOf A getter reference to the VM's [StateFlow] (e.g. `MyVm::state`).
 * @param scope The [TestScope] to use for the VM (pass `this` from inside [runTest]).
 * @param backgroundScope Additional [CoroutineScope] for long-running collectors.
 */
fun <VM, S> testVmContext(
    vm: VM,
    stateOf: (VM) -> StateFlow<S>,
    scope: TestScope,
    backgroundScope: CoroutineScope,
): TestVmContext<VM, S> =
    TestVmContext(vm, stateOf(vm), scope, backgroundScope)

/**
 * Overload that extracts [TestScope.backgroundScope] automatically.
 * Used by the backward-compatible [testVm] overload.
 */
fun <VM, S> testVmContext(
    vm: VM,
    stateOf: (VM) -> StateFlow<S>,
    scope: TestScope,
): TestVmContext<VM, S> =
    TestVmContext(vm, stateOf(vm), scope, scope.backgroundScope)
