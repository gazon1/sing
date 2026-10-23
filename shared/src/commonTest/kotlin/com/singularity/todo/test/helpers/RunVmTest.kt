@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.helpers

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

/**
 * Where the ViewModel under test runs.
 *
 * ## Why this is a name and not a scope
 *
 * The harness used to hand the test two scopes — the test's own and the background
 * one — and let the test pick the one the ViewModel would live in. That is a
 * choice the test cannot make correctly on its own: the right scope depends on
 * what the ViewModel collects, and a test that picks the wrong one does not fail
 * where the mistake is. It fails as a hang, or as a state that never changes, or
 * — worse — as a test that passes because nothing ran.
 *
 * So the harness owns the vocabulary. The test says *what kind of ViewModel it is
 * testing* — one whose work ends, or one whose collector never ends — and the
 * harness builds the scope that kind needs.
 */
enum class VmScope {

    /**
     * The ViewModel's work is the test's work.
     *
     * For a ViewModel whose collectors end, or that are suspended on something
     * that never arrives: [advanceUntilIdle] drives them, and the state the test
     * asserts on is the state the ViewModel produced.
     *
     * The job hangs off the background scope's job, so the ViewModel's work stops
     * when the test ends — the test does not have to remember to cancel it, and
     * closing the ViewModel does not cancel the test.
     */
    Foreground,

    /**
     * The ViewModel's work does not hold the test open.
     *
     * For a ViewModel whose collector never ends *and* keeps producing work: the
     * background scope's marker keeps that work out of [advanceUntilIdle]'s way,
     * so the test is not held open by a collector that will never finish.
     *
     * The state a ViewModel in this scope produces is only as fresh as the test
     * makes it — the test has to say what it waits for.
     */
    Background,
}

/**
 * Creates a [TestVmContext] by instantiating [factory] with the scope the
 * [vmScope] asks for, then advancing the virtual clock to the first idle point.
 *
 * Usage — simple VM test (no event-listening):
 * ```
 * val ctx = testVm(
 *     stateAccessor = { vm: SettingsViewModel -> vm.state },
 * ) { scope ->
 *     SettingsViewModel(deps = deps, scope = scope)
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
 * ) { scope ->
 *     SavedAgendaViewModel(deps = deps, scope = scope)
 * }
 * ctx.act { it.onIntent(SavedAgendaIntent.Delete) }
 * assertTrue(events.any { it is SavedAgendaEvent.DeleteSuccess })
 * ```
 *
 * @param stateAccessor Lambda that extracts the [StateFlow] from the VM (e.g. `::{state}`).
 * @param vmScope Where the ViewModel runs. The harness builds the scope; the test
 *     says which kind of ViewModel it is testing.
 * @param afterInit Optional callback called after [advanceUntilIdle] — use to set up
 *     event listeners on `bgScope` before the first [act] call. Receives the
 *     already-created [VM] and `bgScope`.
 * @param factory Called with the scope the [vmScope] asks for. The ViewModel's `scope`
 *     parameter takes it; nothing else does.
 */
fun <VM, S> TestScope.testVm(
    stateAccessor: (VM) -> StateFlow<S>,
    vmScope: VmScope = VmScope.Foreground,
    afterInit: (VM, bgScope: CoroutineScope) -> Unit = { _, _ -> },
    factory: (scope: AutoCloseableCoroutineScope) -> VM,
): TestVmContext<VM, S> {
    val vm = factory(scopeFor(vmScope))
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
 * The scope a ViewModel of this kind runs in.
 *
 * [VmScope.Foreground] keeps the test's own dispatcher and the test's own
 * background marker — so [advanceUntilIdle] drives the ViewModel's work — and
 * hangs the job off the background scope's job, so the work stops when the test
 * ends without the test having to cancel anything.
 *
 * [VmScope.Background] is the background scope itself: the marker keeps the
 * ViewModel's work out of [advanceUntilIdle]'s way.
 */
private fun TestScope.scopeFor(kind: VmScope): AutoCloseableCoroutineScope = when (kind) {
    VmScope.Foreground -> AutoCloseableCoroutineScope(
        coroutineContext + Job(backgroundScope.coroutineContext[Job]),
    )

    VmScope.Background -> testScope(backgroundScope)
}
