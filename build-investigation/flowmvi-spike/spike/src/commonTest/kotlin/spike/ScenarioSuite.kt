package spike

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import spike.scenario.ListEvent
import spike.scenario.ListIntent
import spike.scenario.ListState
import spike.scenario.TaskFilter
import spike.scenario.TaskListScenario
import spike.scenario.TaskRow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scenarios from the issue, written once and run against both variants.
 *
 * Every function takes a [TaskListScenario] and a fresh [FakeTaskSource]; nothing in it
 * names a framework. If a case cannot be expressed for one variant, that is a finding,
 * not a reason to special-case the suite.
 *
 * ## Why each case settles the scheduler
 *
 * A repository collector publishes on a coroutine, and the test scheduler only runs
 * coroutines when it is advanced. Awaiting inside the body would race the very
 * scheduler that has to publish, so each case advances to idle and then reads the
 * rendered state. That is deterministic for both variants and is what makes the run
 * finish instead of reporting a leaked collector.
 */
object ScenarioSuite {

    /** Applies [intents], lets every coroutine settle, and returns what is on screen. */
    private fun settle(
        scenario: TaskListScenario,
        scope: TestScope,
        intents: List<ListIntent> = emptyList(),
    ): ListState {
        intents.forEach(scenario::intent)
        scope.advanceUntilIdle()
        return scenario.rendered()
    }

    /** 1. The screen shows the loaded rows, and starts in Loading. */
    fun initialLoad(scenario: TaskListScenario, scope: TestScope, source: FakeTaskSource): List<String> {
        assertEquals(ListState.Loading, scenario.rendered(), "both variants start in Loading")
        val ready = settle(scenario, scope) as ListState.Ready
        assertEquals(2, ready.rows.size, "both variants show the loaded rows")
        return ready.rows.map { it.title }
    }

    /** 2. A filter change is reflected in the state and in the rows. */
    fun filterChanges(scenario: TaskListScenario, scope: TestScope, source: FakeTaskSource): List<String> {
        settle(scenario, scope)
        val active = settle(scenario, scope, listOf(ListIntent.SetFilter(TaskFilter.Active))) as ListState.Ready
        assertTrue(active.rows.none { it.completed }, "Active excludes completed rows")

        val completed = settle(
            scenario,
            scope,
            listOf(ListIntent.SetFilter(TaskFilter.Completed)),
        ) as ListState.Ready
        assertTrue(completed.rows.all { it.completed }, "Completed shows only completed rows")
        return completed.rows.map { it.title }
    }

    /** 3. Completing a task comes back through the repository flow. */
    fun taskCompletion(scenario: TaskListScenario, scope: TestScope, source: FakeTaskSource): List<String> {
        settle(scenario, scope)
        val after = settle(scenario, scope, listOf(ListIntent.Complete("1"))) as ListState.Ready
        assertEquals(true, after.rows.first { it.id == "1" }.completed)
        return after.rows.filter { it.completed }.map { it.title }
    }

    /** 4. An insert nobody requested reaches the screen. */
    fun roomFlowUpdates(scenario: TaskListScenario, scope: TestScope, source: FakeTaskSource): List<String> {
        settle(scenario, scope)
        source.externalInsert(TaskRow("3", "Inserted elsewhere", completed = false))
        val after = settle(scenario, scope) as ListState.Ready
        assertEquals(3, after.rows.size)
        return after.rows.map { it.title }
    }

    /**
     * 5. A repository failure surfaces as an event, and the list does not collapse.
     *
     * The error is only observable to the user through the one-shot channel: a failed
     * write leaves no trace in the state, so a variant that swallowed the failure would
     * render exactly the same rows as one that reported it. The first version of this
     * case asserted only the rows and therefore could not tell those two apart — the
     * event is what makes this a comparison of behavior rather than of state.
     */
    fun errorState(scenario: TaskListScenario, scope: TestScope, source: FakeTaskSource): List<String> {
        settle(scenario, scope)
        assertEquals(emptyList(), scenario.events(), "a healthy load emits no event")

        source.failOnComplete = true
        val after = settle(scenario, scope, listOf(ListIntent.Complete("1"))) as ListState.Ready

        assertEquals(2, after.rows.size, "a failed write does not empty the list")
        val messages = scenario.events().map { (it as ListEvent.ShowMessage).message }
        assertEquals(
            1,
            messages.size,
            "a failed write must produce exactly one error event, got: $messages",
        )
        assertTrue(
            messages.single().contains("repository is unavailable"),
            "the error event must carry the failure's own message, got: ${messages.single()}",
        )
        return after.rows.map { it.title }
    }

    /** 6. Twenty rapid intents on one screen: the last one wins, nothing is torn. */
    fun rapidIntents(scenario: TaskListScenario, scope: TestScope, source: FakeTaskSource): List<String> {
        settle(scenario, scope)
        val intents = (0 until 20).map {
            ListIntent.SetFilter(if (it % 2 == 0) TaskFilter.Active else TaskFilter.Completed)
        }
        val finalState = settle(scenario, scope, intents) as ListState.Ready
        assertEquals(TaskFilter.Completed, finalState.filter, "the last intent wins")
        assertTrue(finalState.rows.all { it.completed }, "the last filter is fully applied")
        return listOf("filter=${finalState.filter}, rows=${finalState.rows.size}")
    }
}
