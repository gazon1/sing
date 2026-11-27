package spike

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import spike.scenario.TaskListScenario
import spike.variantA.InRepoTaskListViewModel
import spike.variantB.FlowMviScenario
import spike.variantB.buildTaskListStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The comparison. Every case runs twice — once through the in-repo `MviViewModel`,
 * once through FlowMVI 3.2.1 — and asserts the same observable behavior, so a
 * difference in output is a difference between the frameworks rather than between two
 * test suites.
 *
 * ## Why the work runs on a detached `Job`
 *
 * Both variants collect the repository flow forever, by design. On the test's own `Job`
 * that collector is still active when the body returns, and `runTest` fails with
 * `UncompletedCoroutinesError` — a verdict about a framework that is behaving
 * correctly. A detached child `Job` is cancelled by each case's `close()`, and it is
 * driven by the test dispatcher, so `advanceUntilIdle` settles it. (`backgroundScope`
 * reads better but is not advanced by `advanceUntilIdle` — measured, see FINDINGS.)
 */
class ScenarioComparisonTest {

    private class Harness(val scenario: TaskListScenario, val close: () -> Unit)

    private fun inRepo(scope: TestScope, source: FakeTaskSource): Harness {
        val vmScope = AutoCloseableCoroutineScope(scope.coroutineContext + Job())
        val vm = InRepoTaskListViewModel(source, vmScope)
        return Harness(vm.asScenario(), vmScope::close)
    }

    private fun flowMvi(scope: TestScope, source: FakeTaskSource): Harness {
        val store = buildTaskListStore(source)
        val lifecycle = store.start(CoroutineScope(scope.coroutineContext + Job()))
        val scenario = FlowMviScenario(store, CoroutineScope(scope.coroutineContext + Job()))
        return Harness(
            scenario,
            close = {
                scenario.unsubscribe()
                lifecycle.close()
            },
        )
    }

    /**
     * Runs [case] against both variants on identical inputs and asserts the observable
     * results are equal. Each variant gets its own source, because completing a task
     * mutates it.
     */
    private fun compare(
        case: (TaskListScenario, TestScope, FakeTaskSource) -> List<String>,
    ) = runTest {
        val sourceA = FakeTaskSource()
        val a = inRepo(this, sourceA)
        val viaInRepo = try {
            case(a.scenario, this, sourceA)
        } finally {
            a.close()
        }

        val sourceB = FakeTaskSource()
        val b = flowMvi(this, sourceB)
        val viaFlowMvi = try {
            case(b.scenario, this, sourceB)
        } finally {
            b.close()
        }

        assertEquals(viaInRepo, viaFlowMvi)
    }

    @Test
    fun `initial load matches`() = compare(ScenarioSuite::initialLoad)

    @Test
    fun `filter changes match`() = compare(ScenarioSuite::filterChanges)

    @Test
    fun `task completion matches`() = compare(ScenarioSuite::taskCompletion)

    @Test
    fun `room flow updates match`() = compare(ScenarioSuite::roomFlowUpdates)

    @Test
    fun `error state matches`() = compare(ScenarioSuite::errorState)

    @Test
    fun `rapid intents match`() = compare(ScenarioSuite::rapidIntents)
}
