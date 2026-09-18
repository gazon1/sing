@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.helpers

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Context object for VM testing.
 *
 * Example:
 * ```
 * val ctx = testVmContext(vm, vm::state, backgroundScope)
 * ctx.act { vm.onIntent(Intent.Load) }
 * ctx.assertIs<Loaded>()
 * ```
 */
class TestVmContext<VM, S>(
    val vm: VM,
    val state: StateFlow<S>,
    private val scope: TestScope,
) {
    /** Executes [action] on the VM and waits for pending work. */
    suspend fun act(action: VM.() -> Unit) {
        vm.action()
        scope.advanceUntilIdle()
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
 */
fun <VM, S> testVmContext(
    vm: VM,
    stateOf: (VM) -> StateFlow<S>,
    scope: TestScope,
): TestVmContext<VM, S> = TestVmContext(vm, stateOf(vm), scope)
