@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

/**
 * Creates a [TestVmContext] by instantiating [factory] with the test's [TestScope]
 * and [backgroundScope][TestScope.backgroundScope], then advancing the virtual clock
 * to the first idle point.
 *
 * Usage — simple VM test (no event-listening):
 * ```
 * val ctx = testVm(
 *     stateAccessor = { vm: SettingsViewModel -> vm.state },
 * ) { testScope, _ ->
 *     SettingsViewModel(deps = deps, scope = testScope)
 * }
 * ctx.act { it.onIntent(SettingsIntent.DarkThemeToggled) }
 * ctx.assertIs<SettingsUiState.Content>()
 * ```
 *
 * Usage — event-listening test (e.g. SavedAgenda):
 * ```
 * val events = mutableListOf<SavedAgendaEvent>()
 * val ctx = testVm(
 *     stateAccessor = { vm: SavedAgendaViewModel -> vm.state },
 *     afterInit = { vm, bgScope ->
 *         bgScope.launch { vm.events.collect { events += it } }
 *     },
 * ) { testScope, _ ->
 *     SavedAgendaViewModel(deps = deps, scope = testScope)
 * }
 * ctx.act { it.onIntent(SavedAgendaIntent.Delete) }
 * assertTrue(events.any { it is SavedAgendaEvent.DeleteSuccess })
 * ```
 *
 * @param stateAccessor Lambda that extracts the [StateFlow] from the VM (e.g. `::{state}`).
 * @param afterInit Optional callback called after [advanceUntilIdle] — use to set up
 *     event listeners on `bgScope` before the first [act] call. Receives the
 *     already-created [VM] and `bgScope`.
 * @param factory Called with `testScope` ([this] — the [TestScope]) and `bgScope`
 *     ([TestScope.backgroundScope]) as arguments. Use `testScope` for the VM's `scope`
 *     parameter. Use `bgScope` for long-running collectors (e.g. `vm.events`).
 *     Positioned last so that the trailing-lambda call syntax works without named args.
 */
fun <VM, S> TestScope.testVm(
    stateAccessor: (VM) -> StateFlow<S>,
    afterInit: (VM, bgScope: CoroutineScope) -> Unit = { _, _ -> },
    factory: (testScope: TestScope, bgScope: CoroutineScope) -> VM,
): TestVmContext<VM, S> {
    val vm = factory(this, backgroundScope)
    advanceUntilIdle()
    // advanceUntilIdle() stops as soon as there is no *foreground* work left, so a
    // collector that runs in the background scope — which is where a collector that
    // never ends belongs — has not started yet. runCurrent() dispatches what is
    // scheduled at this moment of virtual time, foreground or not, so the ViewModel
    // has read what it reads before the first assertion. `act` does the same for the
    // same reason.
    runCurrent()
    afterInit(vm, backgroundScope)
    return testVmContext(vm, stateAccessor, this, backgroundScope)
}

/**
 * Backward-compatible overload for tests that need the factory called BEFORE
 * [advanceUntilIdle]. This is required for VMs with never-ending collectors
 * (e.g. `repository.observe().collect {}` in `AttachmentAnnotationViewModel`):
 * calling `advanceUntilIdle()` on a VM that is already running such a collector
 * hangs indefinitely.
 *
 * The VM is created first (inside [factory]), then [advanceUntilIdle] drives the
 * VM's init collectors to completion, and finally [afterInit] is called.
 *
 * Use this overload only when the VM's init collector never terminates.
 * For VMs with completing collectors, prefer the `(TestScope, CoroutineScope) -> VM`
 * overload above.
 *
 * @param stateAccessor Lambda that extracts the [StateFlow] from the VM (e.g. `::{state}`).
 * @param afterInit Optional callback called after [advanceUntilIdle] — use to set up
 *     event listeners on the [TestScope] before the first [act] call.
 * @param factory Called with [this] (the [TestScope]) to create the VM. Runs BEFORE
 *     [advanceUntilIdle] so that VMs with never-ending collectors can be created and
 *     their init collectors driven by [advanceUntilIdle] without hanging.
 */
fun <VM, S> TestScope.testVm(
    stateAccessor: (VM) -> StateFlow<S>,
    afterInit: (VM) -> Unit = {},
    factory: TestScope.() -> VM,
): TestVmContext<VM, S> {
    val vm = factory()
    advanceUntilIdle()
    runCurrent()
    afterInit(vm)
    return testVmContext(vm, stateAccessor, this, backgroundScope)
}
