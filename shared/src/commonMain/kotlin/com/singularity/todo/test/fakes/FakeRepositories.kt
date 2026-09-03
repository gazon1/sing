package com.singularity.todo.test.fakes

import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupResult
import com.singularity.todo.core.backup.ExportOptions
import com.singularity.todo.core.backup.ImportOptions
import com.singularity.todo.core.backup.RestoreResult
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

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
    private val initialUserId: String = "test-user"
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

    override suspend fun setDarkTheme(v: Boolean) { _darkTheme.value = v }
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

// ─── TaskRepository ────────────────────────────────────────────────────────────

class FakeTaskRepository : TaskRepository {
    private val tasks = MutableStateFlow<List<com.singularity.todo.feature.tasks.Task>>(emptyList())
    private val _changes = MutableStateFlow<com.singularity.todo.feature.tasks.Task?>(null)

    override val changes: kotlinx.coroutines.flow.SharedFlow<com.singularity.todo.feature.tasks.Task> =
        kotlinx.coroutines.flow.MutableSharedFlow(extraBufferCapacity = 64)

    override fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<com.singularity.todo.feature.tasks.Task>> =
        tasks.map { all -> all.filter { it.userId == userId } }

    override fun watchTask(id: TaskId): Flow<com.singularity.todo.feature.tasks.Task?> =
        tasks.map { it.find { t -> t.id == id } }

    override fun getTagIds(taskId: TaskId): Flow<List<TagId>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun create(task: com.singularity.todo.feature.tasks.Task): Result<Unit> = runCatching {
        tasks.value = tasks.value + task
        (changes as kotlinx.coroutines.flow.MutableSharedFlow).tryEmit(task)
    }

    override suspend fun update(task: com.singularity.todo.feature.tasks.Task): Result<Unit> = runCatching {
        tasks.value = tasks.value.filter { it.id != task.id } + task
        (changes as kotlinx.coroutines.flow.MutableSharedFlow).tryEmit(task)
    }

    override suspend fun softDelete(id: TaskId): Result<Unit> = runCatching {
        tasks.value = tasks.value.filter { it.id != id }
    }

    override suspend fun restore(id: TaskId): Result<Unit> = Result.success(Unit)
    override suspend fun toggleComplete(id: TaskId): Result<Unit> = runCatching {
        val task = tasks.value.find { it.id == id } ?: return@runCatching
        update(task.copy(
            completedAt = if (task.completedAt != null) null else Instant.fromEpochMilliseconds(0)
        ))
    }

    override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = Result.success(Unit)

    fun addTask(task: com.singularity.todo.feature.tasks.Task) {
        tasks.value = tasks.value + task
    }
}

// ─── NotesRepository ──────────────────────────────────────────────────────────

class FakeNotesRepository : NotesRepository {
    private val notes = MutableStateFlow<List<com.singularity.todo.feature.notes.Note>>(emptyList())

    override fun watchNotes(userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        notes.map { it.filter { n -> n.userId == userId } }

    override fun watchNote(id: com.singularity.todo.feature.notes.NoteId): Flow<com.singularity.todo.feature.notes.Note?> =
        notes.map { it.find { n -> n.id == id } }

    override fun searchNotes(query: String): Flow<List<com.singularity.todo.feature.notes.Note>> =
        notes.map { it.filter { n -> n.title.contains(query, ignoreCase = true) } }

    override suspend fun create(note: com.singularity.todo.feature.notes.Note): Result<Unit> = runCatching {
        notes.value = notes.value + note
    }

    override suspend fun update(note: com.singularity.todo.feature.notes.Note): Result<Unit> = runCatching {
        notes.value = notes.value.filter { it.id != note.id } + note
    }

    override suspend fun softDelete(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        notes.value = notes.value.filter { it.id != id }
    }

    override suspend fun restore(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = Result.success(Unit)
}

// ─── ProjectsRepository ────────────────────────────────────────────────────────

class FakeProjectsRepository : ProjectsRepository {
    private val projects = MutableStateFlow<List<com.singularity.todo.feature.projects.Project>>(emptyList())

    override fun watchProjects(userId: String): Flow<List<com.singularity.todo.feature.projects.Project>> =
        projects.map { it.filter { p -> p.userId == userId } }

    override fun watchProject(id: ProjectId): Flow<com.singularity.todo.feature.projects.Project?> =
        projects.map { it.find { p -> p.id == id } }

    override suspend fun create(project: com.singularity.todo.feature.projects.Project): Result<Unit> = runCatching {
        projects.value = projects.value + project
    }

    override suspend fun update(project: com.singularity.todo.feature.projects.Project): Result<Unit> = runCatching {
        projects.value = projects.value.filter { it.id != project.id } + project
    }

    override suspend fun delete(id: ProjectId): Result<Unit> = runCatching {
        projects.value = projects.value.filter { it.id != id }
    }
}

// ─── TagsRepository ───────────────────────────────────────────────────────────

class FakeTagsRepository : TagsRepository {
    private val tags = MutableStateFlow<List<com.singularity.todo.feature.tags.Tag>>(emptyList())

    override fun watchTags(userId: String): Flow<List<com.singularity.todo.feature.tags.Tag>> =
        tags.map { it.filter { t -> t.userId == userId } }

    override fun watchTag(id: TagId): Flow<com.singularity.todo.feature.tags.Tag?> =
        tags.map { it.find { t -> t.id == id } }

    override suspend fun create(tag: com.singularity.todo.feature.tags.Tag): Result<Unit> = runCatching {
        tags.value = tags.value + tag
    }

    override suspend fun update(tag: com.singularity.todo.feature.tags.Tag): Result<Unit> = runCatching {
        tags.value = tags.value.filter { it.id != tag.id } + tag
    }

    override suspend fun delete(id: TagId): Result<Unit> = runCatching {
        tags.value = tags.value.filter { it.id != id }
    }
}

// ─── AttachmentRepository ─────────────────────────────────────────────────────

class FakeAttachmentRepository : AttachmentRepository {
    private val attachments = MutableStateFlow<List<com.singularity.todo.core.attachments.Attachment>>(emptyList())

    override fun watchByTask(
        taskId: TaskId,
        userId: UserId
    ): Flow<List<com.singularity.todo.core.attachments.Attachment>> =
        attachments.map { it.filter { a -> a.taskId == taskId && a.userId == userId } }

    override suspend fun create(attachment: com.singularity.todo.core.attachments.Attachment): Result<Unit> =
        runCatching { attachments.value = attachments.value + attachment }

    override suspend fun delete(id: AttachmentId): Result<Unit> = runCatching {
        attachments.value = attachments.value.filter { it.id != id }
    }

    override suspend fun saveFileAttachment(
        taskId: TaskId, userId: UserId, sourcePath: String, mimeType: String?
    ): Result<com.singularity.todo.core.attachments.Attachment> = Result.failure(NotImplementedError())

    override suspend fun addUrlAttachment(
        taskId: TaskId, userId: UserId, url: String, title: String?
    ): Result<com.singularity.todo.core.attachments.Attachment> = Result.failure(NotImplementedError())
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
        _backups.value = _backups.value + backup
    }
}

// ─── FileSystem (in-memory) — already exists as MapFileSystem ─────────────────
// Use: MapFileSystem() from com.singularity.todo.core.files.MapFileSystem
