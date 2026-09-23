package com.singularity.todo.feature.settings

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.notifications.NotificationsContributor
import com.singularity.todo.core.notifications.NotificationsSettingsContributor
import com.singularity.todo.core.notifications.NotificationsSettingsStore
import com.singularity.todo.core.schedule.GreetingContributor
import com.singularity.todo.core.schedule.GreetingSettingsContributor
import com.singularity.todo.core.schedule.GreetingSettingsStore
import com.singularity.todo.core.schedule.WorkScheduleContributor
import com.singularity.todo.core.schedule.WorkScheduleSettingsContributor
import com.singularity.todo.core.schedule.WorkScheduleSettingsStore
import com.singularity.todo.core.security.FakeSecureStorage
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.feature.agenda.DefaultAgendaViewContributor
import com.singularity.todo.feature.agenda.DefaultAgendaViewSettingsContributor
import com.singularity.todo.feature.agenda.DefaultAgendaViewSettingsStore
import com.singularity.todo.feature.ai.AiContributor
import com.singularity.todo.feature.ai.AiSettingsContributor
import com.singularity.todo.feature.ai.FakeTextGen
import com.singularity.todo.feature.ai.data.AiSettingsStore
import com.singularity.todo.test.fakes.FakeFileRevealer
import com.singularity.todo.test.fakes.FakeSavedAgendaViewsRepository
import com.singularity.todo.test.fakes.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests [SettingsViewModel] with ALL contributors registered so that [combine]
 * emits on every intent. [advanceUntilIdle] + [runCurrent] flushes the combine
 * collector to process emitted values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val fakeStorage = FakeSecureStorage()
    private val fakeSettings = FakeSettingsRepository()
    private val fakeSavedAgendaViews = FakeSavedAgendaViewsRepository()

    /**
     * Creates a [SettingsViewModel] with all section contributors registered.
     * This ensures the [combine] pipeline emits updated state after each intent.
     */
    private fun createVm(scope: CoroutineScope): SettingsViewModel {
        // AI contributor needs its own AiSettingsStore backed by fakeStorage + fakeSettings.
        val aiStore = AiSettingsStore(fakeStorage, fakeSettings, FakeTextGen())
        val aiContributor: AiContributor = AiSettingsContributor(aiStore)

        // Notifications contributor
        val notificationsStore = NotificationsSettingsStore(fakeSettings.notifications)
        val notificationsContributor: NotificationsContributor = NotificationsSettingsContributor(notificationsStore)

        // Work schedule contributor
        val workScheduleStore = WorkScheduleSettingsStore(fakeSettings.workSchedule)
        val workScheduleContributor: WorkScheduleContributor = WorkScheduleSettingsContributor(workScheduleStore)

        // Greeting contributor
        val greetingStore = GreetingSettingsStore(fakeSettings.greeting)
        val greetingContributor: GreetingContributor = GreetingSettingsContributor(greetingStore)

        // Default agenda view contributor
        val defaultAgendaViewStore = DefaultAgendaViewSettingsStore(fakeSettings.defaultAgendaView)
        val defaultAgendaViewContributor: DefaultAgendaViewContributor = DefaultAgendaViewSettingsContributor(defaultAgendaViewStore)

        return SettingsViewModel(
            scope = testScope(scope),
            appearanceContributor = null,
            notificationsContributor = notificationsContributor,
            workScheduleContributor = workScheduleContributor,
            greetingContributor = greetingContributor,
            aiContributor = aiContributor,
            defaultAgendaViewContributor = defaultAgendaViewContributor,
            savedAgendaViewsRepo = fakeSavedAgendaViews,
            fileRevealer = FakeFileRevealer(),
        )
    }

    // ─── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is Content`() = runTest {
        val vm = createVm(backgroundScope)
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(false, state.appearance.darkTheme)
        assertEquals("blue", state.appearance.accentColor)
        assertEquals(1f, state.appearance.fontSizeScale)
        assertEquals(true, state.notifications.enabled)
    }

    // ─── Appearance ─────────────────────────────────────────────────────────────
    // Appearance has no contributor; state uses defaults. Test verifies defaults.

    @Test
    fun `initial appearance state has correct defaults`() = runTest {
        val vm = createVm(backgroundScope)
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(false, state.appearance.darkTheme)
        assertEquals("blue", state.appearance.accentColor)
        assertEquals(1f, state.appearance.fontSizeScale)
    }

    // ─── Notifications ─────────────────────────────────────────────────────────

    @Test
    fun `Notifications UpdateEnabled updates state`() = runTest {
        val vm = createVm(backgroundScope)
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        vm.processIntent(SettingsIntent.Notifications.UpdateEnabled(false))
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(false, state.notifications.enabled)
    }

    // ─── Work Schedule ────────────────────────────────────────────────────────

    @Test
    fun `WorkSchedule UpdateWorkDayStart updates state`() = runTest {
        val vm = createVm(backgroundScope)
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        vm.processIntent(SettingsIntent.WorkSchedule.UpdateWorkDayStart(600))
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(600, state.workSchedule.dayStartMinutes)
    }

    // ─── Greeting ─────────────────────────────────────────────────────────────

    @Test
    fun `Greeting UpdateMorningEnd updates state`() = runTest {
        val vm = createVm(backgroundScope)
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        vm.processIntent(SettingsIntent.Greeting.UpdateMorningEnd(10))
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(10, state.greeting.morningEndHour)
    }

    // ─── AI ─────────────────────────────────────────────────────────────────

    @Test
    fun `Ai UpdateProvider changes provider`() = runTest {
        val vm = createVm(backgroundScope)
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val job = backgroundScope.launch {
            vm.processIntent(SettingsIntent.Ai.UpdateProvider(com.singularity.todo.core.llm.LlmProvider.OLLAMA))
        }
        job.join()
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals("ollama", state.ai.provider.id)
    }

    @Test
    fun `Ai TestConnection returns Error on missing API key`() = runTest {
        val vm = createVm(backgroundScope)
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        backgroundScope.launch {
            vm.processIntent(SettingsIntent.Ai.TestConnection)
        }
        // Let the VM's scope (backgroundScope) process the launched coroutines
        repeat(10) { advanceUntilIdle() }
        runCurrent()
        val state = vm.state.value as SettingsUiState.Content
        val testResult = state.aiEphemeral.testResult
        assertIs<AiTestResult.Error>(testResult)
        assertEquals("API key not configured", testResult.message)
    }

    @Test
    fun `Ai TestConnection returns Ok on success`() = runTest {
        fakeStorage.write("ai_key_openai", "sk-test")
        val vm = createVm(backgroundScope)
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val job = backgroundScope.launch {
            vm.processIntent(SettingsIntent.Ai.TestConnection)
        }
        job.join()
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val state = vm.state.value as SettingsUiState.Content
        val testResult = state.aiEphemeral.testResult
        assertIs<AiTestResult.Ok>(testResult)
    }

    @Test
    fun `Ai TestConnection returns Error on failure`() = runTest {
        fakeStorage.write("ai_key_openai", "sk-test")
        val textGen = FakeTextGen(failureMessage = "kaboom")
        val aiStore = AiSettingsStore(fakeStorage, fakeSettings, textGen)
        val aiContributor: AiContributor = AiSettingsContributor(aiStore)
        val notificationsStore = NotificationsSettingsStore(fakeSettings.notifications)
        val notificationsContributor: NotificationsContributor = NotificationsSettingsContributor(notificationsStore)
        val workScheduleStore = WorkScheduleSettingsStore(fakeSettings.workSchedule)
        val workScheduleContributor: WorkScheduleContributor = WorkScheduleSettingsContributor(workScheduleStore)
        val greetingStore = GreetingSettingsStore(fakeSettings.greeting)
        val greetingContributor: GreetingContributor = GreetingSettingsContributor(greetingStore)
        val defaultAgendaViewStore = DefaultAgendaViewSettingsStore(fakeSettings.defaultAgendaView)
        val defaultAgendaViewContributor: DefaultAgendaViewContributor = DefaultAgendaViewSettingsContributor(defaultAgendaViewStore)

        val vm = SettingsViewModel(
            scope = testScope(backgroundScope),
            appearanceContributor = null,
            notificationsContributor = notificationsContributor,
            workScheduleContributor = workScheduleContributor,
            greetingContributor = greetingContributor,
            aiContributor = aiContributor,
            defaultAgendaViewContributor = defaultAgendaViewContributor,
            savedAgendaViewsRepo = fakeSavedAgendaViews,
            fileRevealer = FakeFileRevealer(),
        )
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val job = backgroundScope.launch {
            vm.processIntent(SettingsIntent.Ai.TestConnection)
        }
        job.join()
        // Advance until all VM collectors have drained (multiple passes needed
// because advanceUntilIdle() may return before background coroutines settle.
repeat(3) { advanceUntilIdle() }
runCurrent()
        val state = vm.state.value as SettingsUiState.Content
        val testResult = state.aiEphemeral.testResult
        assertIs<AiTestResult.Error>(testResult)
        assertEquals("kaboom", testResult.message)
    }
}
