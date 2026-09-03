package com.singularity.todo.feature.settings

import com.singularity.todo.core.security.FakeSecureStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val fakeStorage = FakeSecureStorage()
    private val fakeSettings = FakeSettingsRepository()

    @Test
    fun `initial state is Content after init`() = runTest {
        val vm = SettingsViewModel(fakeSettings, fakeStorage)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(false, state.darkTheme)
        assertEquals("blue", state.accentColor)
    }

    @Test
    fun `processIntent UpdateDarkTheme updates state`() = runTest {
        val vm = SettingsViewModel(fakeSettings, fakeStorage)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateDarkTheme(true))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(true, state.darkTheme)
    }

    @Test
    fun `processIntent UpdateAccentColor updates state`() = runTest {
        val vm = SettingsViewModel(fakeSettings, fakeStorage)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAccentColor("green"))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals("green", state.accentColor)
    }

    @Test
    fun `processIntent UpdateFontSizeScale updates state`() = runTest {
        val vm = SettingsViewModel(fakeSettings, fakeStorage)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateFontSizeScale(1.25f))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(1.25f, state.fontSizeScale)
    }

    @Test
    fun `processIntent UpdateAiApiKey writes to SecureStorage`() = runTest {
        val vm = SettingsViewModel(fakeSettings, fakeStorage)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiApiKey("sk-test123"))
        advanceUntilIdle()
        assertEquals("sk-test123", fakeStorage.read("ai_key_openai"))
    }

    @Test
    fun `processIntent UpdateAiApiKey with blank deletes from SecureStorage`() = runTest {
        fakeStorage.write("ai_key_openai", "sk-test")
        val vm = SettingsViewModel(fakeSettings, fakeStorage)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateAiApiKey(""))
        advanceUntilIdle()
        assertEquals(null, fakeStorage.read("ai_key_openai"))
    }

    @Test
    fun `processIntent UpdateNotificationsEnabled updates state`() = runTest {
        val vm = SettingsViewModel(fakeSettings, fakeStorage)
        advanceUntilIdle()
        vm.processIntent(SettingsIntent.UpdateNotificationsEnabled(false))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertIs<SettingsUiState.Content>(state)
        assertEquals(false, state.notificationsEnabled)
    }
}
