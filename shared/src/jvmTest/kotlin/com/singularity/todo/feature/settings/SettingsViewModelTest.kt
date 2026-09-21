package com.singularity.todo.feature.settings

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.security.FakeSecureStorage
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.feature.ai.AiSettingsContributor
import com.singularity.todo.feature.ai.FakeTextGen
import com.singularity.todo.feature.ai.data.AiSettingsStore
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeSavedAgendaViewsRepository
import com.singularity.todo.test.fakes.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val fakeStorage = FakeSecureStorage()
    private val fakeSettings = FakeSettingsRepository()
    private val fakeSavedAgendaViews = FakeSavedAgendaViewsRepository()

    private fun createVm(scope: CoroutineScope): SettingsViewModel {
        val aiStore = AiSettingsStore(fakeStorage, fakeSettings, FakeTextGen())
        val aiContributor = AiSettingsContributor(aiStore)
        val contributors: Set<SettingsContributor<*, *>> = setOf(aiContributor)
        return SettingsViewModel(
            contributors = contributors,
            settings = fakeSettings,
            scope = testScope(scope),
            savedAgendaViewsRepo = fakeSavedAgendaViews,
        )
    }

    // ─── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is Content`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(false, state.darkTheme)
        assertEquals("blue", state.accentColor)
        assertEquals(1f, state.fontSizeScale)
        assertEquals(true, state.notificationsEnabled)
    }

    // ─── Appearance ─────────────────────────────────────────────────────────────

    @Test
    fun `UpdateDarkTheme updates state`() = runTest {
        val vm = createVm(backgroundScope)
        vm.processIntent(SettingsIntent.Appearance.UpdateDarkTheme(true))
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(true, state.darkTheme)
    }

    @Test
    fun `UpdateAccentColor updates state`() = runTest {
        val vm = createVm(backgroundScope)
        vm.processIntent(SettingsIntent.Appearance.UpdateAccentColor("green"))
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals("green", state.accentColor)
    }

    @Test
    fun `UpdateFontSizeScale updates state`() = runTest {
        val vm = createVm(backgroundScope)
        vm.processIntent(SettingsIntent.Appearance.UpdateFontSizeScale(1.25f))
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(1.25f, state.fontSizeScale)
    }

    // ─── Notifications ─────────────────────────────────────────────────────────

    @Test
    fun `Notifications UpdateEnabled updates state`() = runTest {
        val vm = createVm(backgroundScope)
        vm.processIntent(SettingsIntent.Notifications.UpdateEnabled(false))
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(false, state.notificationsEnabled)
    }

    // ─── Work Schedule ────────────────────────────────────────────────────────

    @Test
    fun `WorkSchedule UpdateWorkDayStart updates state`() = runTest {
        val vm = createVm(backgroundScope)
        vm.processIntent(SettingsIntent.WorkSchedule.UpdateWorkDayStart(600))
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(600, state.workDayStartMinutes)
    }

    // ─── Greeting ─────────────────────────────────────────────────────────────

    @Test
    fun `Greeting UpdateMorningEnd updates state`() = runTest {
        val vm = createVm(backgroundScope)
        vm.processIntent(SettingsIntent.Greeting.UpdateMorningEnd(10))
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals(10, state.greetingMorningEnd)
    }

    // ─── AI ─────────────────────────────────────────────────────────────────

    @Test
    fun `Ai UpdateProvider changes provider`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        val job = backgroundScope.launch {
            vm.processIntent(SettingsIntent.Ai.UpdateProvider(com.singularity.todo.core.llm.LlmProvider.OLLAMA))
        }
        job.join()
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        assertEquals("ollama", state.aiProvider)
    }

    @Test
    fun `Ai TestConnection returns Error on missing API key`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        val job = backgroundScope.launch {
            vm.processIntent(SettingsIntent.Ai.TestConnection)
        }
        job.join()
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        val testResult = state.aiTestResult
        assertIs<AiTestResult.Error>(testResult)
        assertEquals("API key not configured", testResult.message)
    }

    @Test
    fun `Ai TestConnection returns Ok on success`() = runTest {
        fakeStorage.write("ai_key_openai", "sk-test")
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        val job = backgroundScope.launch {
            vm.processIntent(SettingsIntent.Ai.TestConnection)
        }
        job.join()
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        val testResult = state.aiTestResult
        assertIs<AiTestResult.Ok>(testResult)
    }

    @Test
    fun `Ai TestConnection returns Error on failure`() = runTest {
        fakeStorage.write("ai_key_openai", "sk-test")
        val textGen = FakeTextGen(failureMessage = "kaboom")
        val aiStore = AiSettingsStore(fakeStorage, fakeSettings, textGen)
        val aiContributor = AiSettingsContributor(aiStore)
        val contributors: Set<SettingsContributor<*, *>> = setOf(aiContributor)
        val vm = SettingsViewModel(
            contributors = contributors,
            settings = fakeSettings,
            scope = testScope(backgroundScope),
            savedAgendaViewsRepo = fakeSavedAgendaViews,
        )
        advanceUntilIdle()
        val job = backgroundScope.launch {
            vm.processIntent(SettingsIntent.Ai.TestConnection)
        }
        job.join()
        advanceUntilIdle()
        val state = vm.state.value as SettingsUiState.Content
        val testResult = state.aiTestResult
        assertIs<AiTestResult.Error>(testResult)
        assertEquals("kaboom", testResult.message)
    }
}
