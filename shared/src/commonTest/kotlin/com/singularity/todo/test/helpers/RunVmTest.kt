@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.helpers

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

/**
 * Creates a [TestVmContext] by instantiating [factory] with [this] as the VM's
 * `scope` parameter, then advancing the virtual clock to the first idle point.
 *
 * Usage (inside a test function, `this` is the [TestScope] receiver of [runTest]):
 * ```
 * val ctx = testVm(
 *     stateAccessor = { vm: SettingsViewModel -> vm.state },
 * ) { SettingsViewModel(deps, scope = this) }
 * ctx.act { it.onIntent(SettingsIntent.DarkThemeToggled) }
 * ctx.assertIs<SettingsUiState.Content>()
 * ```
 *
 * @param stateAccessor Lambda that extracts the [StateFlow] from the VM (e.g. `::{state}`).
 * @param factory Called with [this] (the [TestScope]) as the VM's `scope` argument.
 */
fun <VM, S> TestScope.testVm(stateAccessor: (VM) -> StateFlow<S>, factory: TestScope.() -> VM): TestVmContext<VM, S> {
    val vm = factory()
    advanceUntilIdle()
    return testVmContext(vm, stateAccessor, this)
}
