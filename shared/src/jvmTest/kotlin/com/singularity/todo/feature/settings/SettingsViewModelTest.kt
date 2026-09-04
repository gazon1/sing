package com.singularity.todo.feature.settings

import com.singularity.todo.core.security.FakeSecureStorage
import com.singularity.todo.test.fakes.FakeSettingsRepository
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

    private fun createVm() = SettingsViewModel(fakeSettings, fakeStorage)

    // ─── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is Content after init`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(false, state.darkTheme)
        assertEquals("blue", state.accentColor)
        assertEquals(1f, state.fontSizeScale)
        assertEquals(true, state.notificationsEnabled)
    }

    // ─── Appearance intents ────────────────────────────────────────────────────

    @Test
    fun `UpdateDarkTheme updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateDarkTheme(true))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(true, state.darkTheme)
    }

    @Test
    fun `UpdateAccentColor updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAccentColor("green"))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals("green", state.accentColor)
    }

    @Test
    fun `UpdateFontSizeScale updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateFontSizeScale(1.25f))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(1.25f, state.fontSizeScale)
    }

    // ─── Notification intents ──────────────────────────────────────────────────

    @Test
    fun `UpdateNotificationsEnabled updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateNotificationsEnabled(false))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(false, state.notificationsEnabled)
    }

    @Test
    fun `UpdateNotificationSound updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateNotificationSound(false))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(false, state.notificationSound)
    }

    @Test
    fun `UpdateNotificationVibration updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateNotificationVibration(false))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(false, state.notificationVibration)
    }

    @Test
    fun `UpdateReminderDefault updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateReminderDefault(ReminderOffset.ONE_HOUR))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(ReminderOffset.ONE_HOUR, state.reminderDefault)
    }

    // ─── AI intents ───────────────────────────────────────────────────────────

    @Test
    fun `UpdateAiApiKey writes to SecureStorage and updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiApiKey("sk-test123"))
        advanceUntilIdle()
        assertEquals("sk-test123", fakeStorage.read("ai_key_openai"))
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals("sk-test123", state.aiApiKey)
    }

    @Test
    fun `UpdateAiApiKey blank deletes from SecureStorage`() = runTest {
        fakeStorage.write("ai_key_openai", "sk-test")
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiApiKey(""))
        advanceUntilIdle()
        assertEquals(null, fakeStorage.read("ai_key_openai"))
    }

    @Test
    fun `UpdateAiBaseUrl updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiBaseUrl("https://custom.api.com/v1"))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals("https://custom.api.com/v1", state.aiBaseUrl)
    }

    @Test
    fun `UpdateAiModel updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiModel("gpt-4o"))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals("gpt-4o", state.aiModel)
    }

    // ─── Work schedule intents ─────────────────────────────────────────────────

    @Test
    fun `UpdateWorkDayStart updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkDayStart(minutes = 480)) // 08:00
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(480, state.workDayStartMinutes)
    }

    @Test
    fun `UpdateWorkDayEnd updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkDayEnd(minutes = 1020)) // 17:00
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(1020, state.workDayEndMinutes)
    }

    @Test
    fun `UpdateWorkLunchStart updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkLunchStart(minutes = 720)) // 12:00
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(720, state.workLunchStartMinutes)
    }

    @Test
    fun `UpdateWorkLunchEnd updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkLunchEnd(minutes = 780)) // 13:00
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(780, state.workLunchEndMinutes)
    }

    @Test
    fun `UpdateWorkWeekendSat updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkWeekendSat(true))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(true, state.workWeekendSat)
    }

    @Test
    fun `UpdateWorkWeekendSun updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateWorkWeekendSun(true))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(true, state.workWeekendSun)
    }

    // ─── Greeting intents ──────────────────────────────────────────────────────

    @Test
    fun `UpdateGreetingMorningEnd updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateGreetingMorningEnd(hour = 10))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(10, state.greetingMorningEnd)
    }

    @Test
    fun `UpdateGreetingAfternoonEnd updates state`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateGreetingAfternoonEnd(hour = 20))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(20, state.greetingAfternoonEnd)
    }
}
