package com.singularity.todo.test.fakes

import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupResult
import com.singularity.todo.core.backup.ExportOptions
import com.singularity.todo.core.backup.ImportOptions
import com.singularity.todo.core.backup.RestoreResult
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// ─── SessionStore ─────────────────────────────────────────────────────────────

class FakeSessionStore(
    initialUserId: String = "test-user"
) : SessionStore {
    override val accessToken = MutableStateFlow<String?>(null)
    override val refreshToken = MutableStateFlow<String?>(null)
    override val userEmail = MutableStateFlow<String?>(null)
    override val deviceId = MutableStateFlow(initialUserId)

    override suspend fun save(session: com.singularity.todo.core.auth.Session.SignedIn) {
        accessToken.value = session.accessToken
        refreshToken.value = session.refreshToken
        userEmail.value = session.email
    }

    override suspend fun saveDeviceId(id: String) {
        deviceId.value = id
    }

    override suspend fun clear() {
        accessToken.value = null
        refreshToken.value = null
        userEmail.value = null
    }
}

// ─── SettingsRepository ────────────────────────────────────────────────────────

class FakeSettingsRepository(
    initialUserId: String = "test-user"
) : SettingsRepository {
    private val _darkTheme = MutableStateFlow(false)
    private val _accentColor = MutableStateFlow("blue")
    private val _fontSizeScale = MutableStateFlow(1f)
    private val _aiApiKey = MutableStateFlow("")
    private val _aiProvider = MutableStateFlow("openai")
    private val _aiModel = MutableStateFlow("gpt-4o-mini")
    private val _aiBaseUrl = MutableStateFlow("https://api.openai.com/v1")
    private val _aiSystemPrompt = MutableStateFlow(SettingsRepository.DEFAULT_SYSTEM_PROMPT)
    private val _notificationsEnabled = MutableStateFlow(true)
    private val _notificationSound = MutableStateFlow(true)
    private val _notificationVibration = MutableStateFlow(true)
    private val _reminderDefault = MutableStateFlow(com.singularity.todo.feature.settings.ReminderOffset.AT_DUE)
    private val _workDayStartMinutes = MutableStateFlow(540)
    private val _workDayEndMinutes = MutableStateFlow(1080)
    private val _workLunchStartMinutes = MutableStateFlow(720)
    private val _workLunchEndMinutes = MutableStateFlow(780)
    private val _workWeekendSat = MutableStateFlow(false)
    private val _workWeekendSun = MutableStateFlow(false)
    private val _greetingMorningEnd = MutableStateFlow(12)
    private val _greetingAfternoonEnd = MutableStateFlow(18)
    private val _userId = MutableStateFlow(initialUserId)

    override val darkTheme: Flow<Boolean> = _darkTheme
    override val accentColor: Flow<String> = _accentColor
    override val fontSizeScale: Flow<Float> = _fontSizeScale
    override val aiApiKey: Flow<String> = _aiApiKey
    override val aiProvider: Flow<String> = _aiProvider
    override val aiModel: Flow<String> = _aiModel
    override val aiBaseUrl: Flow<String> = _aiBaseUrl
    override val aiSystemPrompt: Flow<String> = _aiSystemPrompt
    override val notificationsEnabled: Flow<Boolean> = _notificationsEnabled
    override val notificationSound: Flow<Boolean> = _notificationSound
    override val notificationVibration: Flow<Boolean> = _notificationVibration
    override val reminderDefault: Flow<com.singularity.todo.feature.settings.ReminderOffset> = _reminderDefault
    override val workDayStartMinutes: Flow<Int> = _workDayStartMinutes
    override val workDayEndMinutes: Flow<Int> = _workDayEndMinutes
    override val workLunchStartMinutes: Flow<Int> = _workLunchStartMinutes
    override val workLunchEndMinutes: Flow<Int> = _workLunchEndMinutes
    override val workWeekendSat: Flow<Boolean> = _workWeekendSat
    override val workWeekendSun: Flow<Boolean> = _workWeekendSun
    override val greetingMorningEnd: Flow<Int> = _greetingMorningEnd
    override val greetingAfternoonEnd: Flow<Int> = _greetingAfternoonEnd
    override val userId: Flow<String> = _userId

    override suspend fun setDarkTheme(value: Boolean) { _darkTheme.value = value }
    override suspend fun setAccentColor(v: String) { _accentColor.value = v }
    override suspend fun setFontSizeScale(v: Float) { _fontSizeScale.value = v }
    override suspend fun setAiApiKey(v: String) { _aiApiKey.value = v }
    override suspend fun setAiProvider(v: String) { _aiProvider.value = v }
    override suspend fun setAiModel(v: String) { _aiModel.value = v }
    override suspend fun setAiBaseUrl(v: String) { _aiBaseUrl.value = v }
    override suspend fun setNotificationsEnabled(v: Boolean) { _notificationsEnabled.value = v }
    override suspend fun setNotificationSound(v: Boolean) { _notificationSound.value = v }
    override suspend fun setNotificationVibration(v: Boolean) { _notificationVibration.value = v }
    override suspend fun setReminderDefault(v: com.singularity.todo.feature.settings.ReminderOffset) { _reminderDefault.value = v }
    override suspend fun setWorkDayStartMinutes(v: Int) { _workDayStartMinutes.value = v }
    override suspend fun setWorkDayEndMinutes(v: Int) { _workDayEndMinutes.value = v }
    override suspend fun setWorkLunchStartMinutes(v: Int) { _workLunchStartMinutes.value = v }
    override suspend fun setWorkLunchEndMinutes(v: Int) { _workLunchEndMinutes.value = v }
    override suspend fun setWorkWeekendSat(v: Boolean) { _workWeekendSat.value = v }
    override suspend fun setWorkWeekendSun(v: Boolean) { _workWeekendSun.value = v }
    override suspend fun setGreetingMorningEnd(v: Int) { _greetingMorningEnd.value = v }
    override suspend fun setGreetingAfternoonEnd(v: Int) { _greetingAfternoonEnd.value = v }
    override suspend fun setUserId(v: String) { _userId.value = v }
}

// ─── BackupRepository ─────────────────────────────────────────────────────────

class FakeBackupRepository : BackupRepository {
    private val _backups = MutableStateFlow<List<BackupMetadata>>(emptyList())

    override val backups: Flow<List<BackupMetadata>> = _backups

    override suspend fun export(options: ExportOptions): Result<BackupResult> = Result.failure(NotImplementedError())
    override suspend fun import(options: ImportOptions): Result<RestoreResult> = Result.failure(NotImplementedError())
    override suspend fun delete(backupId: BackupId): Result<Unit> = runCatching {
        _backups.value = _backups.value.filter { it.id != backupId }
    }
    override suspend fun push(backupId: BackupId): Result<String> = Result.failure(NotImplementedError())
    override suspend fun pull(remoteRef: String, destPath: String): Result<Unit> = Result.failure(NotImplementedError())

    fun addBackup(backup: BackupMetadata) {
        _backups.value += backup
    }
}

// ─── FileSystem (in-memory) — already exists as MapFileSystem ─────────────────
// Use: MapFileSystem() from com.singularity.todo.core.files.MapFileSystem
