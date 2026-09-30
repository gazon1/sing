package com.singularity.todo.test.helpers

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaDeps
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaViewModel
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.core.Koin

/**
 * Asserts that the data layer holds exactly [expected] tasks for the current user.
 *
 * Usage in a flow test — after creating a task through the UI, verify the repository
 * received it before asserting on the screen:
 * ```kotlin
 * runDesktopAppTest { koin ->
 *     assertSeeded(koin, expected = 1)
 *     awaitTag(TestTags.taskItem("Buy milk")).assertIsDisplayed()
 * }
 * ```
 *
 * This pins the failure to the data layer ("repository doesn't know") vs the VM
 * ("screen doesn't render what the repository returns"), replacing a silent
 * "nothing appears" with a concrete count mismatch.
 *
 * @param koin The Koin application under test.
 * @param expected The exact number of tasks the data layer should hold.
 */
@Suppress("NoRunBlocking") // Test helper — blocking bridge is correct here
fun assertSeeded(koin: Koin, expected: Int) {
    val taskRepo = koin.get<TaskRepository>()
    val tasks = runBlocking { taskRepo.observeAll().first() }
    check(tasks.size == expected) {
        "Data layer holds ${tasks.size} tasks, expected $expected. " +
            "Task IDs: ${tasks.map { it.id.value }}"
    }
}

/**
 * Probes the agenda view-model layer by creating an [AgendaViewModel] on the same
 * dependencies as the real screen, waiting for it to settle, and returning its state.
 *
 * Use when the UI layer has a bug and you need to narrow it down to "the VM evaluates
 * the wrong sections" vs "the screen renders the right sections correctly":
 * ```kotlin
 * runDesktopAppTest { koin ->
 *     val agendaState = probeAgenda(koin, AgendaPresets.Inbox)
 *     check(agendaState is AgendaUiState.Loaded) { "VM failed to load: $agendaState" }
 *     val noDateSection = agendaState.sections.find { it.name == "No Date" }
 *     check(noDateSection != null) {
 *         "No Date section missing from: ${agendaState.sections.map { it.name }}"
 *     }
 * }
 * ```
 *
 * Because [AgendaViewModel] is registered via `viewModel { (def: AgendaDefinition) -> ... }`
 * in [com.singularity.todo.feature.agenda.agendaModule], we construct it directly
 * rather than going through `koinViewModel { parametersOf(def) }`, which would use the
 * window-level owner and return the shared instance instead of a probe.
 *
 * @param koin The Koin application under test.
 * @param definition The agenda definition to evaluate.
 * @return The settled [AgendaUiState].
 */
@Suppress("NoRunBlocking") // Test helper — blocking bridge is correct here
fun probeAgenda(koin: Koin, definition: AgendaDefinition): AgendaUiState {
    val taskRepo = koin.get<TaskRepository>()
    val logger = Logger.withTag("probeAgenda")

    // Run on Dispatchers.Main so the VM sees the same dispatcher environment as
    // the real screen. withContext(runTest) is not available in desktop jvmTest,
    // so we use a blocking bridge.
    return runBlocking {
        withContext(Dispatchers.Main) {
            AgendaViewModel(
                deps = AgendaDeps(
                    taskRepo = taskRepo,
                    clock = kotlin.time.Clock.System,
                    logger = logger,
                ),
                definition = definition,
                scope = testScope(this),
            ).let { vm ->
                try {
                    // The VM's init block fires synchronously when constructed on
                    // Dispatchers.Main. Give the collector a turn, then read state.
                    yield()
                    vm.state.value
                } finally {
                    (vm as AutoCloseable).close()
                }
            }
        }
    }
}
