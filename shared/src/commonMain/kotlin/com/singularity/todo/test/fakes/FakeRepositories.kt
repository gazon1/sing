package com.singularity.todo.test.fakes

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.SessionStore
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupResult
import com.singularity.todo.core.backup.ExportOptions
import com.singularity.todo.core.backup.ImportOptions
import com.singularity.todo.core.backup.RestoreResult
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TasksDomain
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.ProfileRepository
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ─── SessionStore ─────────────────────────────────────────────────────────────

class FakeSessionStore(
    initialUserId: String = "test-user"
) : SessionStore {
    override val accessToken = MutableStateFlow<String?>(null)
    override val refreshToken = MutableStateFlow<String?>(null)
    override val userEmail = MutableStateFlow<String?>(null)
    override val deviceId = MutableStateFlow(initialUserId)

    override suspend fun getOrInitDeviceId(): String = deviceId.value

    override suspend fun save(session: Session.SignedIn) {
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
    override suspend fun setAccentColor(value: String) { _accentColor.value = value }
    override suspend fun setFontSizeScale(value: Float) { _fontSizeScale.value = value }
    override suspend fun setAiProvider(value: String) { _aiProvider.value = value }
    override suspend fun setAiModel(value: String) { _aiModel.value = value }
    override suspend fun setAiBaseUrl(value: String) { _aiBaseUrl.value = value }
    override suspend fun setAiSystemPrompt(value: String) { _aiSystemPrompt.value = value }
    override suspend fun setNotificationsEnabled(value: Boolean) { _notificationsEnabled.value = value }
    override suspend fun setNotificationSound(value: Boolean) { _notificationSound.value = value }
    override suspend fun setNotificationVibration(value: Boolean) { _notificationVibration.value = value }
    override suspend fun setReminderDefault(value: com.singularity.todo.feature.settings.ReminderOffset) { _reminderDefault.value = value }
    override suspend fun setWorkDayStartMinutes(value: Int) { _workDayStartMinutes.value = value }
    override suspend fun setWorkDayEndMinutes(value: Int) { _workDayEndMinutes.value = value }
    override suspend fun setWorkLunchStartMinutes(value: Int) { _workLunchStartMinutes.value = value }
    override suspend fun setWorkLunchEndMinutes(value: Int) { _workLunchEndMinutes.value = value }
    override suspend fun setWorkWeekendSat(value: Boolean) { _workWeekendSat.value = value }
    override suspend fun setWorkWeekendSun(value: Boolean) { _workWeekendSun.value = value }
    override suspend fun setGreetingMorningEnd(hour: Int) { _greetingMorningEnd.value = hour }
    override suspend fun setGreetingAfternoonEnd(hour: Int) { _greetingAfternoonEnd.value = hour }
    override suspend fun setUserId(value: String) { _userId.value = value }
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

// ─── TaskRepository ───────────────────────────────────────────────────────────

class FakeTaskRepository : TaskRepository {
    internal val tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    private val _changes = MutableSharedFlow<Task>(extraBufferCapacity = 64)
    override val changes: SharedFlow<Task> = _changes.asSharedFlow()

    /** Seeds tasks by merging into existing state (adds or overwrites by id). */
    fun seed(vararg tasks: Task) {
        this.tasks.value += tasks.associateBy { it.id.value }
    }

    fun add(task: Task) {
        tasks.value += (task.id.value to task)
    }

    fun clear() {
        tasks.value = emptyMap()
    }

    override suspend fun create(task: Task): Result<Unit> = runCatching {
        tasks.value += (task.id.value to task)
        _changes.emit(task)
    }

    override suspend fun update(task: Task): Result<Unit> = runCatching {
        tasks.value += (task.id.value to task)
        _changes.emit(task)
    }

    override suspend fun softDelete(id: TaskId): Result<Unit> = runCatching {
        tasks.value[id.value]?.let { task ->
            val deleted = task.copy(archivedAt = Clock.now())
            tasks.value += (id.value to deleted)
            _changes.emit(deleted)
        }
    }

    override suspend fun restore(id: TaskId): Result<Unit> = runCatching {
        tasks.value[id.value]?.let { task ->
            val restored = task.copy(archivedAt = null)
            tasks.value += (id.value to restored)
            _changes.emit(restored)
        }
    }

    override suspend fun toggleComplete(id: TaskId): Result<Unit> = runCatching {
        tasks.value[id.value]?.let { task ->
            val toggled = if (task.completedAt != null) {
                task.copy(completedAt = null)
            } else {
                task.copy(completedAt = Clock.now())
            }
            tasks.value += (id.value to toggled)
            _changes.emit(toggled)
        }
    }

    override suspend fun togglePinned(id: TaskId): Result<Unit> = runCatching {
        tasks.value[id.value]?.let { task ->
            val toggled = task.copy(isPinned = !task.isPinned)
            tasks.value += (id.value to toggled)
            _changes.emit(toggled)
        }
    }

    override suspend fun exists(id: TaskId): Boolean = tasks.value.containsKey(id.value)

    override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatching {
        tasks.value[taskId.value]?.let { task ->
            val updated = task.copy(tags = tagIds)
            tasks.value += (taskId.value to updated)
        }
    }

    override fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<Task>> =
        tasks.map { map ->
            map.values
                .filter { it.userId == userId }
                .filter { TasksDomain.matchesFilter(it, filter, kotlin.time.Instant.fromEpochMilliseconds(Clock.now().toEpochMilliseconds()).toLocalDateTime(TimeZone.currentSystemDefault()).date) }
                .sortedWith(compareBy({ it.dueDate?.toString() ?: "\uFFFF" }, { !it.isPinned }))
        }

    override fun watchTask(id: TaskId): Flow<Task?> = tasks.map { it[id.value] }

    override fun getTagIds(taskId: TaskId): Flow<List<TagId>> =
        tasks.map { it[taskId.value]?.tags ?: emptyList() }
}

// ─── ChecklistRepository ─────────────────────────────────────────────────────

class FakeChecklistRepository : ChecklistRepository {
    internal val items = MutableStateFlow<Map<String, ChecklistItem>>(emptyMap())

    /** Seeds items by merging into existing state (adds or overwrites by id). */
    fun seed(vararg items: ChecklistItem) {
        this.items.value += items.associateBy { it.id.value }
    }

    fun add(item: ChecklistItem) {
        items.value += (item.id.value to item)
    }

    fun clear() {
        items.value = emptyMap()
    }

    override fun watchByTask(taskId: String): Flow<List<ChecklistItem>> =
        items.map { map -> map.values.filter { it.taskId == taskId }.sortedBy { it.sortOrder } }

    override suspend fun upsert(item: ChecklistItem): Result<Unit> = runCatching {
        items.value += (item.id.value to item)
    }

    override suspend fun delete(id: ChecklistItemId): Result<Unit> = runCatching {
        items.value = items.value.filterKeys { it != id.value }
    }

    override suspend fun createBatch(taskId: String, items: List<ChecklistItem>): Result<Unit> = runCatching {
        this.items.value += items.associateBy { it.id.value }
    }
}

// ─── ReminderRepository ──────────────────────────────────────────────────────

class FakeReminderRepository(
    initialUserId: UserId = UserId("test-user"),
) : ReminderRepository {
    internal val reminders = MutableStateFlow<Map<String, Reminder>>(emptyMap())

    fun seed(vararg reminders: Reminder) {
        this.reminders.value = reminders.associateBy { it.id.value }
    }

    override fun watchAll(userId: UserId): Flow<List<Reminder>> =
        reminders.map { map -> map.values.filter { it.userId == userId }.sortedBy { it.fireAt } }

    override fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<Reminder>> =
        reminders.map { map -> map.values.filter { it.taskId == taskId && it.userId == userId }.sortedBy { it.fireAt } }

    override fun watchDueBefore(nowEpochMs: Long, userId: UserId): Flow<List<Reminder>> =
        reminders.map { map -> map.values.filter { it.fireAt <= nowEpochMs && it.userId == userId }.sortedBy { it.fireAt } }

    override suspend fun upsert(reminder: Reminder): Result<Unit> = runCatching {
        reminders.value += (reminder.id.value to reminder)
    }

    override suspend fun delete(reminderId: ReminderId, userId: UserId): Result<Unit> = runCatching {
        reminders.value = reminders.value.filterKeys { it != reminderId.value }
    }

    override suspend fun deleteByTask(taskId: TaskId, userId: UserId): Result<Unit> = runCatching {
        reminders.value = reminders.value.filterValues { it.taskId != taskId || it.userId != userId }
    }

    override suspend fun getById(reminderId: ReminderId, userId: UserId): Result<Reminder?> = runCatching {
        reminders.value[reminderId.value]
    }
}

// ─── AuthRepository ───────────────────────────────────────────────────────────

class FakeAuthRepository(
    initialSession: Session = Session.Anonymous(UserId.anonymous)
) : AuthRepository {
    private val _session = MutableStateFlow(initialSession)
    override val session: StateFlow<Session> = _session.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    override suspend fun signUp(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signIn(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInAnonymously(): Result<Unit> = Result.success(Unit)
    override suspend fun signOut(): Result<Unit> = runCatching {
        _session.value = Session.SignedOut
    }

    override suspend fun migrateAnonymousTo(newUserId: UserId): Result<Unit> = Result.success(Unit)
}

// ─── ProjectsRepository ──────────────────────────────────────────────────────

class FakeProjectsRepository : com.singularity.todo.feature.projects.ProjectsRepository {
    private val store = mutableMapOf<String, com.singularity.todo.feature.projects.Project>()
    private val _flow = MutableStateFlow<List<com.singularity.todo.feature.projects.Project>>(emptyList())

    fun seed(vararg projects: com.singularity.todo.feature.projects.Project) {
        projects.forEach { store[it.id.value] = it }
        emit()
    }

    fun add(project: com.singularity.todo.feature.projects.Project) {
        store[project.id.value] = project
        emit()
    }

    fun clear() {
        store.clear()
        emit()
    }

    private fun emit() { _flow.value = store.values.toList() }

    override fun watchProjects(userId: String): Flow<List<com.singularity.todo.feature.projects.Project>> =
        _flow.map { list -> list.filter { it.userId == userId && !it.isDeleted } }

    override fun watchProject(id: com.singularity.todo.feature.projects.ProjectId): Flow<com.singularity.todo.feature.projects.Project?> =
        _flow.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun create(project: com.singularity.todo.feature.projects.Project): Result<Unit> = runCatching {
        store[project.id.value] = project
        emit()
    }

    override suspend fun update(project: com.singularity.todo.feature.projects.Project): Result<Unit> = runCatching {
        store[project.id.value] = project
        emit()
    }

    override suspend fun delete(id: com.singularity.todo.feature.projects.ProjectId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store[id.value] = existing.copy(isDeleted = true, deletedAt = Clock.now())
            emit()
        }
    }
}

// ─── TagsRepository ──────────────────────────────────────────────────────────

class FakeTagsRepository : com.singularity.todo.feature.tags.TagsRepository {
    private val store = mutableMapOf<String, com.singularity.todo.feature.tags.Tag>()
    private val _flow = MutableStateFlow<List<com.singularity.todo.feature.tags.Tag>>(emptyList())

    fun seed(vararg tags: com.singularity.todo.feature.tags.Tag) {
        tags.forEach { store[it.id.value] = it }
        emit()
    }

    fun add(tag: com.singularity.todo.feature.tags.Tag) {
        store[tag.id.value] = tag
        emit()
    }

    fun clear() {
        store.clear()
        emit()
    }

    private fun emit() { _flow.value = store.values.toList() }

    override fun watchTags(userId: String): Flow<List<com.singularity.todo.feature.tags.Tag>> =
        _flow.map { list -> list.filter { it.userId == userId } }

    override fun watchTag(id: TagId): Flow<com.singularity.todo.feature.tags.Tag?> =
        _flow.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun create(tag: com.singularity.todo.feature.tags.Tag): Result<Unit> = runCatching {
        store[tag.id.value] = tag
        emit()
    }

    override suspend fun update(tag: com.singularity.todo.feature.tags.Tag): Result<Unit> = runCatching {
        store[tag.id.value] = tag
        emit()
    }

    override suspend fun delete(id: TagId): Result<Unit> = runCatching {
        store.remove(id.value)
        emit()
    }
}

// ─── AttachmentRepository ────────────────────────────────────────────────────

class FakeAttachmentRepository : com.singularity.todo.core.attachments.AttachmentRepository {
    private val store = mutableMapOf<String, com.singularity.todo.core.attachments.Attachment>()
    private val _flow = MutableStateFlow<List<com.singularity.todo.core.attachments.Attachment>>(emptyList())

    fun seed(vararg attachments: com.singularity.todo.core.attachments.Attachment) {
        attachments.forEach { store[it.id.value] = it }
        emit()
    }

    private fun emit() { _flow.value = store.values.toList() }

    override fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<com.singularity.todo.core.attachments.Attachment>> =
        _flow.map { list -> list.filter { it.taskId == taskId && it.userId == userId } }

    override suspend fun create(attachment: com.singularity.todo.core.attachments.Attachment): Result<Unit> = runCatching {
        store[attachment.id.value] = attachment
        emit()
    }

    override suspend fun delete(id: com.singularity.todo.core.attachments.AttachmentId): Result<Unit> = runCatching {
        store.remove(id.value)
        emit()
    }

    override suspend fun saveFileAttachment(
        taskId: TaskId,
        userId: UserId,
        sourcePath: String,
        mimeType: String?,
    ): Result<com.singularity.todo.core.attachments.Attachment> = runCatching {
        val att = com.singularity.todo.core.attachments.Attachment(
            id = com.singularity.todo.core.attachments.AttachmentId.generate(),
            taskId = taskId,
            userId = userId,
            type = com.singularity.todo.core.attachments.AttachmentType.File,
            localPath = sourcePath,
            mimeType = mimeType,
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
        )
        store[att.id.value] = att
        emit()
        att
    }

    override suspend fun addUrlAttachment(
        taskId: TaskId,
        userId: UserId,
        url: String,
        title: String?,
    ): Result<com.singularity.todo.core.attachments.Attachment> = runCatching {
        val att = com.singularity.todo.core.attachments.Attachment(
            id = com.singularity.todo.core.attachments.AttachmentId.generate(),
            taskId = taskId,
            userId = userId,
            type = com.singularity.todo.core.attachments.AttachmentType.Url,
            url = url,
            title = title ?: "",
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
        )
        store[att.id.value] = att
        emit()
        att
    }
}

// ─── NotesRepository ─────────────────────────────────────────────────────────

class FakeNotesRepository : com.singularity.todo.feature.notes.NotesRepository {
    val notes: Map<String, com.singularity.todo.feature.notes.Note> get() = store
    private val store = mutableMapOf<String, com.singularity.todo.feature.notes.Note>()
    private val _flow = MutableStateFlow<List<com.singularity.todo.feature.notes.Note>>(emptyList())

    fun seed(note: com.singularity.todo.feature.notes.Note) {
        store[note.id.value] = note
        emit()
    }

    fun add(note: com.singularity.todo.feature.notes.Note) {
        store[note.id.value] = note
        emit()
    }

    fun clear() {
        store.clear()
        emit()
    }

    private fun emit() {
        _flow.value = store.values.toList()
    }

    override fun watchNotes(userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        _flow.map { list -> list.filter { it.userId == userId && it.deletedAt == null } }

    override fun watchPinned(userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        _flow.map { list -> list.filter { it.userId == userId && it.isPinned && it.deletedAt == null } }

    override fun watchArchived(userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        _flow.map { list -> list.filter { it.userId == userId && it.archivedAt != null && it.deletedAt == null } }

    override fun watchRootNotes(userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        _flow.map { list -> list.filter { it.userId == userId && it.parentNoteId == null && !it.isFolder && it.deletedAt == null } }

    override fun watchNote(id: com.singularity.todo.feature.notes.NoteId): Flow<com.singularity.todo.feature.notes.Note?> =
        _flow.map { list -> list.firstOrNull { it.id == id } }

    override fun searchNotes(query: String): Flow<List<com.singularity.todo.feature.notes.Note>> =
        _flow.map { list ->
            list.filter { note -> note.deletedAt == null && (note.title.contains(query, ignoreCase = true) || (note.bodyMarkdown?.contains(query, ignoreCase = true) == true)) }
        }

    override suspend fun create(note: com.singularity.todo.feature.notes.Note): Result<Unit> = runCatching {
        store[note.id.value] = note
        emit()
    }

    override suspend fun update(note: com.singularity.todo.feature.notes.Note): Result<Unit> = runCatching {
        store[note.id.value] = note
        emit()
    }

    override suspend fun createWithContent(
        userId: UserId,
        id: com.singularity.todo.feature.notes.NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<com.singularity.todo.feature.notes.NoteId> = runCatching {
        val now = Clock.now()
        store[id.value] = com.singularity.todo.feature.notes.Note(
            id = id,
            userId = userId,
            title = title,
            bodyMarkdown = bodyMarkdown,
            bodyHtml = bodyHtml,
            wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() },
            charCount = bodyMarkdown.length,
            createdAt = now,
            updatedAt = now,
        )
        emit()
        id
    }

    override suspend fun updateContent(
        id: com.singularity.todo.feature.notes.NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store[id.value] = existing.copy(
                title = title,
                bodyMarkdown = bodyMarkdown,
                bodyHtml = bodyHtml,
                wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() },
                charCount = bodyMarkdown.length,
                updatedAt = Clock.now(),
            )
            emit()
        }
    }

    override suspend fun softDelete(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store[id.value] = existing.copy(deletedAt = Clock.now())
            emit()
        }
    }

    override suspend fun restore(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store[id.value] = existing.copy(deletedAt = null)
            emit()
        }
    }

    override suspend fun archive(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store[id.value] = existing.copy(archivedAt = Clock.now())
            emit()
        }
    }

    override suspend fun unarchive(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store[id.value] = existing.copy(archivedAt = null)
            emit()
        }
    }

    override suspend fun setPinned(id: com.singularity.todo.feature.notes.NoteId, pinned: Boolean): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store[id.value] = existing.copy(isPinned = pinned, pinnedAt = if (pinned) Clock.now() else null)
            emit()
        }
    }

    override suspend fun setColor(id: com.singularity.todo.feature.notes.NoteId, color: com.singularity.todo.feature.notes.NoteColor?): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store[id.value] = existing.copy(color = color)
            emit()
        }
    }

    override suspend fun setSortOrder(id: com.singularity.todo.feature.notes.NoteId, sortOrder: Int): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store[id.value] = existing.copy(sortOrder = sortOrder)
            emit()
        }
    }
}

// ─── ProfileRepository ──────────────────────────────────────────────────────────

/**
 * In-memory [ProfileRepository] for tests.
 * Provides a default "Personal" profile so the app works without a real DataStore.
 */
class FakeProfileRepository : ProfileRepository {

    private val _profiles = MutableStateFlow(
        listOf(
            Profile(
                id = ProfileId.default,
                name = "Personal",
                emoji = "🏠",
                colorIdx = 0,
                isDefault = true,
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
            )
        )
    )

    private val _activeProfileId = MutableStateFlow(ProfileId.default)

    override fun all(): Flow<List<Profile>> = _profiles

    override fun activeProfile(): Flow<Profile> = _activeProfileId.map { id ->
        _profiles.value.find { it.id == id } ?: _profiles.value.first()
    }

    override val activeProfileId: StateFlow<ProfileId> = _activeProfileId

    override suspend fun create(name: String, emoji: String, colorIdx: Int): ProfileId {
        val id = ProfileId.generate()
        _profiles.value = _profiles.value + Profile(
            id = id,
            name = name,
            emoji = emoji,
            colorIdx = colorIdx,
            isDefault = false,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            updatedAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        )
        return id
    }

    override suspend fun update(id: ProfileId, name: String, emoji: String, colorIdx: Int) {
        _profiles.value = _profiles.value.map {
            if (it.id == id) it.copy(name = name, emoji = emoji, colorIdx = colorIdx)
            else it
        }
    }

    override suspend fun delete(id: ProfileId): Result<Unit> {
        if (_profiles.value.size <= 1) {
            return Result.failure(IllegalStateException("Cannot delete the last remaining profile"))
        }
        _profiles.value = _profiles.value.filter { it.id != id }
        if (_activeProfileId.value == id) {
            _activeProfileId.value = _profiles.value.first().id
        }
        return Result.success(Unit)
    }

    override suspend fun switchTo(id: ProfileId) {
        _activeProfileId.value = id
    }

    override suspend fun getById(id: ProfileId): Profile? =
        _profiles.value.find { it.id == id }
}

/**
 * Builds a [ProfileAwareCurrentUser] from a [FakeAuthRepository] + [FakeProfileRepository].
 */
fun FakeProfileAwareCurrentUser(
    authRepository: AuthRepository = FakeAuthRepository(),
    profileRepository: ProfileRepository = FakeProfileRepository(),
): ProfileAwareCurrentUser = ProfileAwareCurrentUser(
    currentUser = CurrentUser(authRepository),
    profileRepository = profileRepository,
)
