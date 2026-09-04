package com.singularity.todo.feature.settings

import com.singularity.todo.core.security.FakeSecureStorage
import com.singularity.todo.test.fakes.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val fakeStorage = FakeSecureStorage()
    private val fakeSettings = FakeSettingsRepository()

    /** Unconfined dispatcher runs launchFlow collectors synchronously — no
     *  advanceUntilIdle juggling required after the first one. */
    private fun createVm(scope: CoroutineScope): SettingsViewModel =
        SettingsViewModel(fakeSettings, fakeStorage, scopeOverride = scope)

    // ─── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is Content after init`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertIs<SettingsUiState.Content>(state)
        assertEquals(false, state.darkTheme)
        assertEquals("blue", state.accentColor)
        assertEquals(1f, state.fontSizeScale)
        assertEquals(true, state.notificationsEnabled)
    }

    // ─── Appearance intents ────────────────────────────────────────────────────

    @Test
    fun `UpdateDarkTheme updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateDarkTheme(true))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertIs<SettingsUiState.Content>(state)
        assertEquals(true, state.darkTheme)
    }

    @Test
    fun `UpdateAccentColor updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAccentColor("green"))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals("green", state.accentColor)
    }

    @Test
    fun `UpdateFontSizeScale updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateFontSizeScale(1.5f))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(1.5f, state.fontSizeScale)
    }

    // ─── Notification intents ──────────────────────────────────────────────────

    @Test
    fun `UpdateNotificationsEnabled updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateNotificationsEnabled(false))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(false, state.notificationsEnabled)
    }

    @Test
    fun `UpdateNotificationSound updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateNotificationSound(false))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(false, state.notificationSound)
    }

    @Test
    fun `UpdateNotificationVibration updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateNotificationVibration(false))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(false, state.notificationVibration)
    }

    @Test
    fun `UpdateReminderDefault updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateReminderDefault(com.singularity.todo.feature.settings.ReminderOffset.FIFTEEN_MIN))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(com.singularity.todo.feature.settings.ReminderOffset.FIFTEEN_MIN, state.reminderDefault)
    }

    // ─── AI intents ────────────────────────────────────────────────────────────

    @Test
    fun `UpdateAiApiKey writes to secureStorage and settings`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiApiKey("sk-test"))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals("sk-test", state.aiApiKey)
        assertEquals("sk-test", fakeStorage.read("ai_key_openai"))
    }

    @Test
    fun `UpdateAiApiKey blank deletes from SecureStorage`() = runTest {
        // First write something, then clear it
        fakeStorage.write("ai_key_openai", "sk-test")
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiApiKey(""))
        advanceUntilIdle()
        assertNull(fakeStorage.read("ai_key_openai"))
    }

    @Test
    fun `UpdateAiBaseUrl updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiBaseUrl("https://api.test.com/v1"))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals("https://api.test.com/v1", state.aiBaseUrl)
    }

    @Test
    fun `UpdateAiModel updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiModel("gpt-4"))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals("gpt-4", state.aiModel)
    }

    // ─── Work schedule intents ─────────────────────────────────────────────────

    @Test
    fun `UpdateWorkDayStart updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkDayStart(420))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(420, state.workDayStartMinutes)
    }

    @Test
    fun `UpdateWorkDayEnd updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkDayEnd(1200))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(1200, state.workDayEndMinutes)
    }

    @Test
    fun `UpdateWorkLunchStart updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkLunchStart(720))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(720, state.workLunchStartMinutes)
    }

    @Test
    fun `UpdateWorkLunchEnd updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkLunchEnd(780))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(780, state.workLunchEndMinutes)
    }

    @Test
    fun `UpdateWorkWeekendSat updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkWeekendSat(true))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(true, state.workWeekendSat)
    }

    @Test
    fun `UpdateWorkWeekendSun updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkWeekendSun(true))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(true, state.workWeekendSun)
    }

    // ─── Greeting intents ──────────────────────────────────────────────────────

    @Test
    fun `UpdateGreetingMorningEnd updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateGreetingMorningEnd(11))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(11, state.greetingMorningEnd)
    }

    @Test
    fun `UpdateGreetingAfternoonEnd updates state`() = runTest {
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateGreetingAfternoonEnd(20))
        advanceUntilIdle()
        val state = vm.uiState.value as SettingsUiState.Content
        assertEquals(20, state.greetingAfternoonEnd)
    }
}
