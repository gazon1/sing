package com.singularity.todo.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake of [SettingsRepository] for testing.
 * Backed by [MutableStateFlow] fields — all updates are synchronous in tests.
 */
class FakeSettingsRepository : SettingsRepository(FakeDataStore()) {

    private val _darkTheme = MutableStateFlow(false)
    private val _accentColor = MutableStateFlow("blue")
    private val _fontSizeScale = MutableStateFlow(1f)
    private val _notificationsEnabled = MutableStateFlow(true)
    private val _notificationSound = MutableStateFlow(true)
    private val _notificationVibration = MutableStateFlow(true)
    private val _reminderDefault = MutableStateFlow(ReminderOffset.AT_DUE)
    private val _aiApiKey = MutableStateFlow("")
    private val _aiBaseUrl = MutableStateFlow("https://api.openai.com/v1")
    private val _aiModel = MutableStateFlow("gpt-4o-mini")
    private val _workDayStartMinutes = MutableStateFlow(540)
    private val _workDayEndMinutes = MutableStateFlow(1080)
    private val _workLunchStartMinutes = MutableStateFlow(720)
    private val _workLunchEndMinutes = MutableStateFlow(780)
    private val _workWeekendSat = MutableStateFlow(false)
    private val _workWeekendSun = MutableStateFlow(false)
    private val _greetingMorningEnd = MutableStateFlow(12)
    private val _greetingAfternoonEnd = MutableStateFlow(18)
    private val _userId = MutableStateFlow("test-user")

    override val darkTheme: Flow<Boolean> = _darkTheme
    override val accentColor: Flow<String> = _accentColor
    override val fontSizeScale: Flow<Float> = _fontSizeScale
    override val notificationsEnabled: Flow<Boolean> = _notificationsEnabled
    override val notificationSound: Flow<Boolean> = _notificationSound
    override val notificationVibration: Flow<Boolean> = _notificationVibration
    override val reminderDefault: Flow<ReminderOffset> = _reminderDefault
    override val aiApiKey: Flow<String> = _aiApiKey
    override val aiBaseUrl: Flow<String> = _aiBaseUrl
    override val aiModel: Flow<String> = _aiModel
    override val workDayStartMinutes: Flow<Int> = _workDayStartMinutes
    override val workDayEndMinutes: Flow<Int> = _workDayEndMinutes
    override val workLunchStartMinutes: Flow<Int> = _workLunchStartMinutes
    override val workLunchEndMinutes: Flow<Int> = _workLunchEndMinutes
    override val workWeekendSat: Flow<Boolean> = _workWeekendSat
    override val workWeekendSun: Flow<Boolean> = _workWeekendSun
    override val greetingMorningEnd: Flow<Int> = _greetingMorningEnd
    override val greetingAfternoonEnd: Flow<Int> = _greetingAfternoonEnd
    override val userId: Flow<String> = _userId

    override fun userIdBlocking(): String = _userId.value

    // Override setters to emit into state flows
    override suspend fun setDarkTheme(value: Boolean) { _darkTheme.value = value }
    override suspend fun setAccentColor(value: String) { _accentColor.value = value }
    override suspend fun setFontSizeScale(value: Float) { _fontSizeScale.value = value }
    override suspend fun setNotificationsEnabled(value: Boolean) { _notificationsEnabled.value = value }
    override suspend fun setNotificationSound(value: Boolean) { _notificationSound.value = value }
    override suspend fun setNotificationVibration(value: Boolean) { _notificationVibration.value = value }
    override suspend fun setReminderDefault(value: ReminderOffset) { _reminderDefault.value = value }
    override suspend fun setAiApiKey(value: String) { _aiApiKey.value = value }
    override suspend fun setAiBaseUrl(value: String) { _aiBaseUrl.value = value }
    override suspend fun setAiModel(value: String) { _aiModel.value = value }
    override suspend fun setWorkDayStartMinutes(value: Int) { _workDayStartMinutes.value = value }
    override suspend fun setWorkDayEndMinutes(value: Int) { _workDayEndMinutes.value = value }
    override suspend fun setWorkLunchStartMinutes(value: Int) { _workLunchStartMinutes.value = value }
    override suspend fun setWorkLunchEndMinutes(value: Int) { _workLunchEndMinutes.value = value }
    override suspend fun setWorkWeekendSat(value: Boolean) { _workWeekendSat.value = value }
    override suspend fun setWorkWeekendSun(value: Boolean) { _workWeekendSun.value = value }
    override suspend fun setGreetingMorningEnd(hour: Int) { _greetingMorningEnd.value = hour }
    override suspend fun setGreetingAfternoonEnd(hour: Int) { _greetingAfternoonEnd.value = hour }
}

private class FakeDataStore : DataStore<Preferences> {
    private val stateFlow = MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
    override val data: Flow<Preferences> = stateFlow
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(stateFlow.value)
        stateFlow.value = updated
        return updated
    }
}
