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
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.ProjectWithCountRow
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.ProfileRepository
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewKey
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ─── SessionStore ─────────────────────────────────────────────────────────────

class FakeSessionStore(initialUserId: String = "test-user") : SessionStore {
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

class FakeSettingsRepository(initialUserId: String = "test-user") : SettingsRepository {
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
    private val _reminderDefault = MutableStateFlow(com.singularity.todo.core.reminders.ReminderOffset.AT_DUE)
    private val _workDayStartMinutes = MutableStateFlow(540)
    private val _workDayEndMinutes = MutableStateFlow(1080)
    private val _workLunchStartMinutes = MutableStateFlow(720)
    private val _workLunchEndMinutes = MutableStateFlow(780)
    private val _workWeekendSat = MutableStateFlow(false)
    private val _workWeekendSun = MutableStateFlow(false)
    private val _greetingMorningEnd = MutableStateFlow(12)
    private val _greetingAfternoonEnd = MutableStateFlow(18)
    private val _userId = MutableStateFlow(initialUserId)
    private val _defaultSavedAgendaViewId = MutableStateFlow<SavedAgendaViewId?>(null)

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
    override val reminderDefault: Flow<com.singularity.todo.core.reminders.ReminderOffset> = _reminderDefault
    override val workDayStartMinutes: Flow<Int> = _workDayStartMinutes
    override val workDayEndMinutes: Flow<Int> = _workDayEndMinutes
    override val workLunchStartMinutes: Flow<Int> = _workLunchStartMinutes
    override val workLunchEndMinutes: Flow<Int> = _workLunchEndMinutes
    override val workWeekendSat: Flow<Boolean> = _workWeekendSat
    override val workWeekendSun: Flow<Boolean> = _workWeekendSun
    override val greetingMorningEnd: Flow<Int> = _greetingMorningEnd
    override val greetingAfternoonEnd: Flow<Int> = _greetingAfternoonEnd
    override val userId: Flow<String> = _userId
    override val defaultSavedAgendaViewId: Flow<SavedAgendaViewId?> = _defaultSavedAgendaViewId

    override suspend fun setDarkTheme(value: Boolean) {
        _darkTheme.value = value
    }
    override suspend fun setAccentColor(value: String) {
        _accentColor.value = value
    }
    override suspend fun setFontSizeScale(value: Float) {
        _fontSizeScale.value = value
    }
    override suspend fun setAiProvider(value: String) {
        _aiProvider.value = value
    }
    override suspend fun setAiModel(value: String) {
        _aiModel.value = value
    }
    override suspend fun setAiBaseUrl(value: String) {
        _aiBaseUrl.value = value
    }
    override suspend fun setAiSystemPrompt(value: String) {
        _aiSystemPrompt.value = value
    }
    override suspend fun setNotificationsEnabled(value: Boolean) {
        _notificationsEnabled.value = value
    }
    override suspend fun setNotificationSound(value: Boolean) {
        _notificationSound.value = value
    }
    override suspend fun setNotificationVibration(value: Boolean) {
        _notificationVibration.value = value
    }
    override suspend fun setReminderDefault(
        value: com.singularity.todo.core.reminders.ReminderOffset,
    ) {
        _reminderDefault.value =
        value
    }
    override suspend fun setWorkDayStartMinutes(value: Int) {
        _workDayStartMinutes.value = value
    }
    override suspend fun setWorkDayEndMinutes(value: Int) {
        _workDayEndMinutes.value = value
    }
    override suspend fun setWorkLunchStartMinutes(value: Int) {
        _workLunchStartMinutes.value = value
    }
    override suspend fun setWorkLunchEndMinutes(value: Int) {
        _workLunchEndMinutes.value = value
    }
    override suspend fun setWorkWeekendSat(value: Boolean) {
        _workWeekendSat.value = value
    }
    override suspend fun setWorkWeekendSun(value: Boolean) {
        _workWeekendSun.value = value
    }
    override suspend fun setGreetingMorningEnd(hour: Int) {
        _greetingMorningEnd.value = hour
    }
    override suspend fun setGreetingAfternoonEnd(hour: Int) {
        _greetingAfternoonEnd.value = hour
    }
    override suspend fun setUserId(value: String) {
        _userId.value = value
    }

    override suspend fun setDefaultSavedAgendaViewId(id: SavedAgendaViewId?) {
        _defaultSavedAgendaViewId.value = id
    }
}

// ─── BackupRepository ─────────────────────────────────────────────────────────

class FakeBackupRepository : BackupRepository {
    private val _backups = MutableStateFlow<List<BackupMetadata>>(emptyList())
    override val backups: Flow<List<BackupMetadata>> = _backups

    // ─── Recording fields (for assertions) ───────────────────────────────────
    var lastExportOptions: ExportOptions? = null
        private set
    var lastImportOptions: ImportOptions? = null
        private set
    var lastDeletedId: BackupId? = null
        private set
    var lastPushedId: BackupId? = null
        private set

    // ─── Configurable results (set in tests) ─────────────────────────────────
    var exportResult: Result<BackupResult> = Result.failure(NotImplementedError("export not configured"))
    var importResult: Result<RestoreResult> = Result.failure(NotImplementedError("import not configured"))
    var deleteResult: Result<Unit> = Result.success(Unit)
    var pushResult: Result<String> = Result.success("https://remote/backup.zip")

    fun addBackup(backup: BackupMetadata) {
        _backups.value += backup
    }

    fun clearRecordings() {
        lastExportOptions = null
        lastImportOptions = null
        lastDeletedId = null
        lastPushedId = null
    }

    override suspend fun export(options: ExportOptions): Result<BackupResult> {
        lastExportOptions = options
        return exportResult
    }

    override suspend fun import(options: ImportOptions): Result<RestoreResult> {
        lastImportOptions = options
        return importResult
    }

    override suspend fun delete(backupId: BackupId): Result<Unit> {
        lastDeletedId = backupId
        _backups.value = _backups.value.filter { it.id != backupId }
        return deleteResult
    }

    override suspend fun push(backupId: BackupId): Result<String> {
        lastPushedId = backupId
        return pushResult
    }

    override suspend fun pull(remoteRef: String, destPath: String): Result<Unit> = Result.failure(NotImplementedError())
}

// ─── FileSystem (in-memory) — already exists as MapFileSystem ─────────────────
// Use: MapFileSystem() from com.singularity.todo.core.files.MapFileSystem

// ─── TaskRepository ───────────────────────────────────────────────────────────

/**
 * In-memory [TaskDao] implementation for tests.
 * Stores only dependency and tag cross-references; all other methods error.
 */
private class InMemoryTaskDao : TaskDao {
    private val _deps = MutableStateFlow<List<TaskDependencyCrossRef>>(emptyList())
    private val _tags = MutableStateFlow<List<TaskTagCrossRef>>(emptyList())

    // ── Dependency methods (the only ones used by FakeTaskRepository) ─────────

    override fun getDependencyIdsForTask(taskId: String): Flow<List<String>> =
        _deps.map { refs -> refs.filter { it.taskId == taskId }.map { it.dependsOnTaskId } }

    override fun getBlockingTaskIdsForTask(taskId: String): Flow<List<String>> =
        _deps.map { refs -> refs.filter { it.dependsOnTaskId == taskId }.map { it.taskId } }

    override suspend fun upsertDependency(ref: TaskDependencyCrossRef) {
        _deps.update { current -> current.filter { !(it.taskId == ref.taskId && it.dependsOnTaskId == ref.dependsOnTaskId) } + ref }
    }

    override suspend fun removeDependency(taskId: String, depId: String) {
        _deps.update { current -> current.filter { !(it.taskId == taskId && it.dependsOnTaskId == depId) } }
    }

    override suspend fun clearDependencies(taskId: String) {
        _deps.update { current -> current.filter { it.taskId != taskId } }
    }

    // ── Tag methods (stubs so the interface is satisfied) ─────────────────────

    override fun getTagIdsForTask(taskId: String): Flow<List<String>> =
        _tags.map { refs -> refs.filter { it.taskId == taskId }.map { it.tagId } }

    override suspend fun upsertTagCrossRef(ref: TaskTagCrossRef) {
        _tags.update { current -> current.filter { !(it.taskId == ref.taskId && it.tagId == ref.tagId) } + ref }
    }

    override suspend fun removeTagRef(taskId: String, tagId: String) {
        _tags.update { current -> current.filter { !(it.taskId == taskId && it.tagId == tagId) } }
    }

    // ── Remaining DAO methods (unused by FakeTaskRepository) ──────────────────

    override fun watchActive(userId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchById(id: String): Flow<com.singularity.todo.core.database.TaskEntity?> =
        error("not implemented")

    override fun watchTrash(userId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchSomeday(userId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByDate(userId: String, date: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchUpcoming(userId: String, today: String, endDate: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByProject(userId: String, projectId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByDateRange(userId: String, from: String, to: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByTag(userId: String, tagId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByAnyTag(userId: String, tagIds: List<String>): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByAllTags(userId: String, tagIds: List<String>, size: Int): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByPriorities(userId: String, priorities: List<String>): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByRegexp(userId: String, pattern: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchPinned(userId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override suspend fun setPinned(id: String, pinned: Boolean, ts: Long) = error("not implemented")

    override suspend fun getById(id: String): com.singularity.todo.core.database.TaskEntity? = error("not implemented")

    override fun watchSearchResults(userId: String, q: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> = error("not implemented")

    override suspend fun searchTitles(userId: String, q: String): List<com.singularity.todo.core.database.TaskEntity> = error("not implemented")

    override suspend fun upsert(task: com.singularity.todo.core.database.TaskEntity) = error("not implemented")

    override suspend fun softDelete(id: String, ts: Long) = error("not implemented")

    override suspend fun restore(id: String, ts: Long) = error("not implemented")

    override suspend fun markComplete(id: String, ts: Long) = error("not implemented")

    override suspend fun markIncomplete(id: String, ts: Long) = error("not implemented")

    override suspend fun listAllForUser(userId: String): List<com.singularity.todo.core.database.TaskEntity> = error("not implemented")

    override suspend fun listAllDependenciesForUser(userId: String): List<TaskDependencyCrossRef> = error("not implemented")

    override suspend fun listAllTagsForUser(userId: String): List<TaskTagCrossRef> = error("not implemented")

    override suspend fun archiveCompleted(ts: Long): Int = error("not implemented")
}

class FakeTaskRepository(
    private val dao: TaskDao = InMemoryTaskDao(),
    private val explicitCurrentUser: ProfileAwareCurrentUser? = null,
) : TaskRepository {
    private val store = InMemoryStore<Task>(keyOf = { it.id.value })
    private val _changes = MutableSharedFlow<Task>(extraBufferCapacity = 64)
    override val changes: SharedFlow<Task> = _changes.asSharedFlow()

    // The effective currentUser — either injected (for tests) or lazily resolved from singleton.
    private val currentUser: ProfileAwareCurrentUser
        get() = explicitCurrentUser ?: ProfileAwareCurrentUser.instance ?: FakeProfileAwareCurrentUser()

    /** Expose store state as [StateFlow] for [watchTasks] and other flows. */
    internal val tasks: StateFlow<Map<String, Task>> = store.state

    /** Seeds tasks by merging into existing state (adds or overwrites by id). */
    fun seed(vararg tasks: Task) = store.seed(tasks.toList())

    fun add(task: Task) = store.upsert(task)

    fun clear() = store.clear()

    // ── GenericUserScopedRepository implementation ────────────────────────────────

    override fun observeAll(): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        store.state
            .onStart { emit(store.state.value) }
            .map { map -> map.values.filter { it.userId == uid }.toList() }
    }

    override fun observe(id: TaskId): Flow<Task?> =
        store.state.onStart { emit(store.state.value) }.map { it[id.value] }

    override suspend fun create(item: Task): Result<Task> = runCatching {
        store.upsert(item)
        _changes.emit(item)
        item
    }

    override suspend fun update(item: Task): Result<Task> = runCatching {
        store.upsert(item)
        _changes.emit(item)
        item
    }

    override suspend fun delete(id: TaskId): Result<Unit> = runCatching {
        store.remove(id.value)
    }

    // ── Domain-specific user-scoped observers ────────────────────────────────────

    override fun observeByFilter(filter: TaskFilter): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            store.state
                .onStart { emit(store.state.value) }
                .map { map ->
                    map.values
                        .filter { it.userId == uid }
                        .filter {
                            TaskDomain.matchesFilter(
                                it,
                                filter,
                                kotlin.time.Instant.fromEpochMilliseconds(
                                    Clock.now().toEpochMilliseconds(),
                                ).toLocalDateTime(TimeZone.currentSystemDefault()).date,
                            )
                        }
                        .sortedWith(compareBy({ it.dueDate?.toString() ?: "\uFFFF" }, { !it.isPinned }))
                }
        }

    override fun observeByDate(date: kotlinx.datetime.LocalDate): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            store.state
                .onStart { emit(store.state.value) }
                .map { map ->
                    map.values
                        .filter { it.userId == uid }
                        .filter { !it.isTrashed && !it.someday && it.dueDate == date }
                        .sortedWith(compareBy({ !it.isPinned }))
                }
        }

    override fun observeSubtasks(parentId: TaskId): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            store.state
                .onStart { emit(store.state.value) }
                .map { map -> map.values.filter { it.parentTaskId == parentId && it.userId == uid } }
        }

    override fun observeDependencies(taskId: TaskId): Flow<Set<TaskId>> =
        dao.getDependencyIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    override fun observeBlockingBy(taskId: TaskId): Flow<Set<TaskId>> =
        dao.getBlockingTaskIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    // ── Legacy / DAO-backed methods ─────────────────────────────────────────────

    override suspend fun softDelete(id: TaskId): Result<Unit> = runCatching {
        store[id.value]?.let { task ->
            val deleted = task.copy(archivedAt = Clock.now())
            store.upsert(deleted)
            _changes.emit(deleted)
        }
    }

    override suspend fun get(id: TaskId): Task? = store[id.value]

    // getByIdForCurrentUser intentionally omitted — use getById + caller-side userId check

    override suspend fun restore(id: TaskId): Result<Unit> = runCatching {
        store[id.value]?.let { task ->
            val restored = task.copy(archivedAt = null)
            store.upsert(restored)
            _changes.emit(restored)
        }
    }

    override suspend fun toggleComplete(id: TaskId): Result<Unit> = runCatching {
        store[id.value]?.let { task ->
            val toggled = if (task.completedAt != null) {
                task.copy(completedAt = null)
            } else {
                task.copy(completedAt = Clock.now())
            }
            store.upsert(toggled)
            _changes.emit(toggled)
        }
    }

    override suspend fun togglePinned(id: TaskId): Result<Unit> = runCatching {
        store[id.value]?.let { task ->
            val toggled = task.copy(isPinned = !task.isPinned)
            store.upsert(toggled)
            _changes.emit(toggled)
        }
    }

    override suspend fun exists(id: TaskId): Boolean = store.contains(id.value)

    override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatching {
        store[taskId.value]?.let { task ->
            val updated = task.copy(tags = tagIds)
            store.upsert(updated)
        }
    }

    // Legacy observation methods kept for binary compat — not part of TaskRepository interface.
    fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<Task>> = store.state
        .onStart { emit(store.state.value) }
        .map { map ->
            map.values
                .filter { it.userId == userId }
                .filter {
                    TaskDomain.matchesFilter(
                        it,
                        filter,
                        kotlin.time.Instant.fromEpochMilliseconds(
                            Clock.now().toEpochMilliseconds(),
                        ).toLocalDateTime(TimeZone.currentSystemDefault()).date,
                    )
                }
                .sortedWith(compareBy({ it.dueDate?.toString() ?: "\uFFFF" }, { !it.isPinned }))
        }

    fun watchTask(id: TaskId): Flow<Task?> =
        store.state.onStart { emit(store.state.value) }.map { it[id.value] }

    fun watchTasksByDate(userId: UserId, date: kotlinx.datetime.LocalDate): Flow<List<Task>> = store.state
        .onStart { emit(store.state.value) }
        .map { map ->
            map.values
                .filter { it.userId == userId }
                .filter { !it.isTrashed && !it.someday && it.dueDate == date }
                .sortedWith(compareBy({ !it.isPinned }))
        }

    fun watchSubtasks(parentId: TaskId, userId: UserId): Flow<List<Task>> = store.state
        .onStart { emit(store.state.value) }
        .map { map -> map.values.filter { it.parentTaskId == parentId && it.userId.value == userId.value } }

    override fun getTagIds(taskId: TaskId): Flow<List<TagId>> =
        store.state.map { it[taskId.value]?.tags ?: emptyList() }

    override fun watchDependencies(taskId: TaskId): Flow<Set<TaskId>> =
        dao.getDependencyIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    override fun watchBlockingBy(taskId: TaskId): Flow<Set<TaskId>> =
        dao.getBlockingTaskIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    override suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit> = runCatching {
        dao.clearDependencies(taskId.value)
        deps.forEach { dep ->
            dao.upsertDependency(TaskDependencyCrossRef(taskId = taskId.value, dependsOnTaskId = dep.value))
        }
    }
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
    private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(),
) : ReminderRepository {
    internal val reminders = MutableStateFlow<Map<String, Reminder>>(emptyMap())

    fun seed(vararg reminders: Reminder) {
        this.reminders.value = reminders.associateBy { it.id.value }
    }

    // ─── UserId-free observation (Phase 2 pattern) ───────────────────────────────

    override fun watchAllForCurrentUser(): Flow<List<Reminder>> =
        currentUser.observeForCurrentUser { uid ->
            reminders.map { map -> map.values.filter { it.userId == uid }.sortedBy { it.fireAt } }
        }

    override fun watchByTaskForCurrentUser(taskId: TaskId): Flow<List<Reminder>> =
        currentUser.observeForCurrentUser { uid ->
            reminders.map { map -> map.values.filter { it.taskId == taskId && it.userId == uid }.sortedBy { it.fireAt } }
        }

    override fun watchDueBeforeForCurrentUser(nowEpochMs: Long): Flow<List<Reminder>> =
        currentUser.observeForCurrentUser { uid ->
            reminders.map { map -> map.values.filter { it.fireAt <= nowEpochMs && it.userId == uid }.sortedBy { it.fireAt } }
        }

    // ─── Explicit userId overloads ──────────────────────────────────────────

    override fun watchAll(userId: UserId): Flow<List<Reminder>> =
        reminders.map { map -> map.values.filter { it.userId == userId }.sortedBy { it.fireAt } }

    override fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<Reminder>> =
        reminders.map { map -> map.values.filter { it.taskId == taskId && it.userId == userId }.sortedBy { it.fireAt } }

    override fun watchDueBefore(nowEpochMs: Long, userId: UserId): Flow<List<Reminder>> = reminders.map { map ->
        map.values.filter { it.fireAt <= nowEpochMs && it.userId == userId }.sortedBy { it.fireAt }
    }

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

class FakeAuthRepository(initialSession: Session = Session.Anonymous(UserId.anonymous)) : AuthRepository {
    private val _session = MutableStateFlow(initialSession)
    // Return _session directly — MutableStateFlow IS a StateFlow, so this satisfies
    // the interface. Using asStateFlow() creates a new wrapper each call, which
    // breaks shared subscription state between callers.
    override val session: StateFlow<Session> = _session

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    override suspend fun signUp(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signIn(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInAnonymously(): Result<Unit> = Result.success(Unit)
    override suspend fun signOut(): Result<Unit> = runCatching {
        _session.value = Session.SignedOut
    }

    override suspend fun migrateAnonymousTo(newUserId: UserId): Result<Unit> = Result.success(Unit)

    /**
     * Switches the session to a new anonymous user with [userId].
     * For use in tests that simulate profile/user switches.
     */
    fun setUserId(userId: UserId) {
        _session.value = Session.Anonymous(userId)
    }
}

// ─── ProjectsRepository ──────────────────────────────────────────────────────

class FakeProjectsRepository(
    private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(),
) : ProjectsRepository {
    internal val store = InMemoryStore<Project>(
        keyOf = { it.id.value },
    )

    fun seed(vararg projects: Project) = store.seed(projects.toList())
    fun add(project: Project) = store.upsert(project)
    fun clear() = store.clear()

    // ─── UserId-free observation (Phase 2 pattern) ───────────────────────────

    override fun observeAllForCurrentUser(): Flow<List<Project>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.filter { it.userId == uid && !it.isDeleted } }
        }

    override fun observeProjectsWithCountsForCurrentUser(): Flow<List<ProjectWithCountRow>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values
                    .filter { it.userId == uid && !it.isDeleted }
                    .map { p ->
                        ProjectWithCountRow(
                            project = ProjectEntity(
                                id = p.id.value, userId = p.userId.value, name = p.name, color = p.color,
                                icon = p.icon, description = p.description, createdAt = p.createdAt.toEpochMilliseconds(),
                                updatedAt = p.updatedAt.toEpochMilliseconds(), isDefault = p.isDefault,
                                dueDate = p.dueDate?.toString(), team = p.team, isDeleted = p.isDeleted,
                                deletedAt = p.deletedAt?.toEpochMilliseconds(), parentId = p.parentId?.value,
                                sortOrder = p.sortOrder, idempotencyKey = p.idempotencyKey, externalId = p.externalId,
                                sync = SyncColumns(),
                            ),
                            totalCount = 0,
                            completedCount = 0,
                        )
                    }
            }
        }

    override fun watchProjectForCurrentUser(id: ProjectId): Flow<Project?> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
        }

    override suspend fun getByIdForCurrentUser(id: ProjectId): Project? {
        val uid = currentUser.scopedUserId.value
        return store[id.value]?.takeIf { it.userId == uid }
    }

    override fun changesForCurrentUser(id: ProjectId): Flow<Project?> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
        }

    override fun watchChildrenOfForCurrentUser(parentId: ProjectId): Flow<List<Project>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values.filter { it.parentId == parentId && it.userId == uid && !it.isDeleted }
            }
        }

    // ─── Explicit userId overloads ──────────────────────────────────────────

    override fun watchProjects(userId: UserId): Flow<List<Project>> =
        store.state.map { list -> list.values.filter { it.userId == userId && !it.isDeleted } }

    override fun watchProject(id: ProjectId): Flow<Project?> =
        store.state.map { list -> list.values.firstOrNull { it.id == id } }

    override suspend fun getById(id: ProjectId): Project? = store[id.value]

    override fun changes(id: ProjectId): Flow<Project?> =
        store.state.map { list -> list.values.firstOrNull { it.id == id } }

    override fun watchProjectsWithCounts(userId: UserId): Flow<List<ProjectWithCountRow>> = store.state.map { list ->
        list.values
            .filter { it.userId == userId && !it.isDeleted }
            .map { p ->
                ProjectWithCountRow(
                    project = ProjectEntity(
                        id = p.id.value, userId = p.userId.value, name = p.name, color = p.color,
                        icon = p.icon, description = p.description, createdAt = p.createdAt.toEpochMilliseconds(),
                        updatedAt = p.updatedAt.toEpochMilliseconds(), isDefault = p.isDefault,
                        dueDate = p.dueDate?.toString(), team = p.team, isDeleted = p.isDeleted,
                        deletedAt = p.deletedAt?.toEpochMilliseconds(), parentId = p.parentId?.value,
                        sortOrder = p.sortOrder, idempotencyKey = p.idempotencyKey, externalId = p.externalId,
                        sync = SyncColumns(),
                    ),
                    totalCount = 0,
                    completedCount = 0,
                )
            }
    }

    override fun watchByParent(parentId: ProjectId): Flow<List<Project>> =
        store.state.map { list -> list.values.filter { it.parentId == parentId && !it.isDeleted } }

    override suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long) {
        store[id.value]?.let { existing ->
            store.upsert(
                existing.copy(parentId = parentId, updatedAt = kotlin.time.Instant.fromEpochMilliseconds(updatedAt)),
            )
        }
    }

    override suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long) {
        store[id.value]?.let { existing ->
            store.upsert(
                existing.copy(sortOrder = sortOrder, updatedAt = kotlin.time.Instant.fromEpochMilliseconds(updatedAt)),
            )
        }
    }

    override suspend fun restore(id: ProjectId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(isDeleted = false, deletedAt = null))
        }
    }

    override suspend fun findByIdempotencyKey(key: String): Project? =
        store.values().firstOrNull { it.idempotencyKey == key }

    override suspend fun create(project: Project): Result<Unit> = runCatching {
        store.upsert(project)
    }

    override suspend fun update(project: Project): Result<Unit> = runCatching {
        store.upsert(project)
    }

    override suspend fun delete(id: ProjectId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(isDeleted = true, deletedAt = Clock.now()))
        }
    }
}

// ─── TagsRepository ──────────────────────────────────────────────────────────

class FakeTagsRepository(
    private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(),
) : com.singularity.todo.feature.tags.TagsRepository {
    private val store = InMemoryStore<com.singularity.todo.feature.tags.Tag>(keyOf = { it.id.value })

    fun seed(vararg tags: com.singularity.todo.feature.tags.Tag) = store.seed(tags.toList())
    fun add(tag: com.singularity.todo.feature.tags.Tag) = store.upsert(tag)
    fun clear() = store.clear()

    // ─── UserId-free observation (Phase 2 pattern) ───────────────────────────────

    override fun watchTagsForCurrentUser(): Flow<List<com.singularity.todo.feature.tags.Tag>> =
        currentUser.observeForCurrentUser { uid ->
            store.state
                .onStart { emit(store.state.value) }
                .map { list -> list.values.filter { it.userId == uid.value } }
        }

    // ─── Explicit userId overloads ──────────────────────────────────────────

    override fun watchTags(userId: String): Flow<List<com.singularity.todo.feature.tags.Tag>> = store.state
        .onStart { emit(store.state.value) }
        .map { list -> list.values.filter { it.userId == userId } }

    override fun watchTag(id: TagId): Flow<com.singularity.todo.feature.tags.Tag?> =
        store.state.map { list -> list.values.firstOrNull { it.id == id } }

    override suspend fun create(tag: com.singularity.todo.feature.tags.Tag): Result<Unit> = runCatching {
        store.upsert(tag)
    }

    override suspend fun update(tag: com.singularity.todo.feature.tags.Tag): Result<Unit> = runCatching {
        store.upsert(tag)
    }

    override suspend fun delete(id: TagId): Result<Unit> = runCatching {
        store.remove(id.value)
    }
}

// ─── AttachmentRepository ────────────────────────────────────────────────────

class FakeAttachmentRepository(
    private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(),
) : com.singularity.todo.core.attachments.AttachmentRepository {
    private val store = InMemoryStore<com.singularity.todo.core.attachments.Attachment>(keyOf = { it.id.value })

    fun seed(vararg attachments: com.singularity.todo.core.attachments.Attachment) = store.seed(attachments.toList())

    // ─── UserId-free reads (Phase 2 pattern) ─────────────────────────────────

    override fun watchByTaskForCurrentUser(taskId: TaskId): Flow<List<com.singularity.todo.core.attachments.Attachment>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.filter { it.taskId == taskId && it.userId == uid } }
        }

    override fun watchByTask(
        taskId: TaskId,
        userId: UserId,
    ): Flow<List<com.singularity.todo.core.attachments.Attachment>> =
        store.state.map { list -> list.values.filter { it.taskId == taskId && it.userId == userId } }

    override suspend fun create(attachment: com.singularity.todo.core.attachments.Attachment): Result<Unit> =
        runCatching {
            store.upsert(attachment)
        }

    override suspend fun delete(id: com.singularity.todo.core.attachments.AttachmentId): Result<Unit> = runCatching {
        store.remove(id.value)
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
        store.upsert(att)
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
        store.upsert(att)
        att
    }
}

// ─── NotesRepository ─────────────────────────────────────────────────────────

class FakeNotesRepository(
    private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(),
) : com.singularity.todo.feature.notes.NotesRepository {
    /** Exposes raw store map for tests that need direct map access. */
    val notes: Map<String, com.singularity.todo.feature.notes.Note> get() = store.state.value
    private val store = InMemoryStore<com.singularity.todo.feature.notes.Note>(keyOf = { it.id.value })

    fun seed(note: com.singularity.todo.feature.notes.Note) = store.upsert(note)
    fun add(note: com.singularity.todo.feature.notes.Note) = store.upsert(note)
    fun clear() = store.clear()

    // ─── UserId-free reads (Phase 2 pattern) ─────────────────────────────────

    override fun searchNotesForCurrentUser(query: String): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values.filter { note ->
                    note.userId == uid && note.deletedAt == null && (
                        note.title.contains(query, ignoreCase = true) ||
                            (note.bodyMarkdown?.contains(query, ignoreCase = true) == true)
                    )
                }
            }
        }

    override fun watchNotesForCurrentUser(): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.filter { it.userId == uid && it.deletedAt == null } }
        }

    override fun watchPinnedForCurrentUser(): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.filter { it.userId == uid && it.isPinned && it.deletedAt == null } }
        }

    override fun watchArchivedForCurrentUser(): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values.filter { it.userId == uid && it.archivedAt != null && it.deletedAt == null }
            }
        }

    override fun watchNoteForCurrentUser(id: com.singularity.todo.feature.notes.NoteId): Flow<com.singularity.todo.feature.notes.Note?> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
        }

    override suspend fun getNoteByIdForCurrentUser(id: com.singularity.todo.feature.notes.NoteId): com.singularity.todo.feature.notes.Note? {
        val uid = currentUser.scopedUserId.value
        return store.state.value.values.firstOrNull { it.id == id && it.userId == uid }
    }

    override fun watchRootNotesForCurrentUser(): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values.filter { it.userId == uid && it.parentNoteId == null && !it.isFolder && it.deletedAt == null }
            }
        }

    // ─── Explicit userId reads ───────────────────────────────────────────────

    override fun watchNotes(userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        store.state.map { list -> list.values.filter { it.userId == userId && it.deletedAt == null } }

    override fun watchPinned(userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        store.state.map { list -> list.values.filter { it.userId == userId && it.isPinned && it.deletedAt == null } }

    override fun watchArchived(userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        store.state.map { list ->
            list.values.filter {
                it.userId == userId && it.archivedAt != null &&
                    it.deletedAt == null
            }
        }

    override fun watchRootNotes(userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        store.state.map { list ->
            list.values.filter {
                it.userId == userId && it.parentNoteId == null && !it.isFolder &&
                    it.deletedAt == null
            }
        }

    override fun watchNote(
        id: com.singularity.todo.feature.notes.NoteId,
    ): Flow<com.singularity.todo.feature.notes.Note?> =
        store.state.map { list -> list.values.firstOrNull { it.id == id } }

    override fun searchNotes(query: String, userId: UserId): Flow<List<com.singularity.todo.feature.notes.Note>> =
        store.state.map { list ->
            list.values.filter { note ->
                note.userId == userId && note.deletedAt == null && (
                    note.title.contains(query, ignoreCase = true) ||
                        (note.bodyMarkdown?.contains(query, ignoreCase = true) == true)
                )
            }
        }

    // ─── Deprecated (remove in Phase 3) ─────────────────────────────────────

    override fun searchNotes(query: String): Flow<List<com.singularity.todo.feature.notes.Note>> =
        store.state.map { list ->
            list.values.filter { note ->
                note.deletedAt == null && (
                    note.title.contains(
                    query,
                    ignoreCase = true,
                ) || (note.bodyMarkdown?.contains(query, ignoreCase = true) == true)
                )
            }
        }

    override suspend fun create(note: com.singularity.todo.feature.notes.Note): Result<Unit> = runCatching {
        store.upsert(note)
    }

    override suspend fun update(note: com.singularity.todo.feature.notes.Note): Result<Unit> = runCatching {
        store.upsert(note)
    }

    override suspend fun createWithContent(
        userId: UserId,
        id: com.singularity.todo.feature.notes.NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<com.singularity.todo.feature.notes.NoteId> = runCatching {
        val now = Clock.now()
        val note = com.singularity.todo.feature.notes.Note(
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
        store.upsert(note)
        id
    }

    override suspend fun createNoteWithTitle(
        userId: UserId,
        title: String,
    ): Result<com.singularity.todo.feature.notes.NoteId> = runCatching {
        val id = com.singularity.todo.feature.notes.NoteId(com.singularity.todo.core.ids.nextId())
        val now = Clock.now()
        val note = com.singularity.todo.feature.notes.Note(
            id = id,
            userId = userId,
            title = title,
            bodyMarkdown = null,
            bodyHtml = null,
            wordCount = 0,
            charCount = 0,
            createdAt = now,
            updatedAt = now,
        )
        store.upsert(note)
        id
    }

    override suspend fun updateContent(
        id: com.singularity.todo.feature.notes.NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(
                existing.copy(
                    title = title,
                    bodyMarkdown = bodyMarkdown,
                    bodyHtml = bodyHtml,
                    wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() },
                    charCount = bodyMarkdown.length,
                    updatedAt = Clock.now(),
                ),
            )
        }
    }

    override suspend fun softDelete(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(deletedAt = Clock.now()))
        }
    }

    override suspend fun restore(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(deletedAt = null))
        }
    }

    override suspend fun archive(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(archivedAt = Clock.now()))
        }
    }

    override suspend fun unarchive(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(archivedAt = null))
        }
    }

    override suspend fun setPinned(id: com.singularity.todo.feature.notes.NoteId, pinned: Boolean): Result<Unit> =
        runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(isPinned = pinned, pinnedAt = if (pinned) Clock.now() else null))
            }
        }

    override suspend fun setColor(
        id: com.singularity.todo.feature.notes.NoteId,
        color: com.singularity.todo.feature.notes.NoteColor?,
    ): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(color = color))
        }
    }

    override suspend fun setSortOrder(id: com.singularity.todo.feature.notes.NoteId, sortOrder: Int): Result<Unit> =
        runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(sortOrder = sortOrder))
            }
        }

    override suspend fun setOutgoingLinks(
        id: com.singularity.todo.feature.notes.NoteId,
        links: List<String>,
    ): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(outgoingLinks = links))
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
            ),
        ),
    )

    private val _activeProfileId = MutableStateFlow(ProfileId.default)

    override fun all(): Flow<List<Profile>> = _profiles

    override fun activeProfile(): Flow<Profile> = _activeProfileId.map { id ->
        _profiles.value.find { it.id == id } ?: _profiles.value.first()
    }

    override val activeProfileId: StateFlow<ProfileId> = _activeProfileId

    override suspend fun create(name: String, emoji: String, colorIdx: Int): ProfileId {
        val id = ProfileId.generate()
        _profiles.value += Profile(
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
            if (it.id == id) {
                it.copy(name = name, emoji = emoji, colorIdx = colorIdx)
            } else {
                it
            }
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

    override suspend fun getById(id: ProfileId): Profile? = _profiles.value.find { it.id == id }

    override suspend fun ensureDefaults(extraProfiles: List<Triple<String, String, Int>>) {
        // In-memory fake: just append any missing extras; default already present
        // by the initial value of [_profiles].
        for ((name, emoji, colorIdx) in extraProfiles) {
            if (_profiles.value.any { it.name == name }) continue
            _profiles.value += Profile(
                id = ProfileId.generate(),
                name = name,
                emoji = emoji,
                colorIdx = colorIdx,
                isDefault = false,
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            )
        }
    }
}

/**
 * Builds a [ProfileAwareCurrentUser] from a [FakeAuthRepository] + [FakeProfileRepository].
 *
 * Use [initialUserId] to set the starting user ID — useful in tests where
 * `scopedUserId.value` would otherwise be `UserId.anonymous` before the combine
 * produces its first emission.
 *
 * @param scope CoroutineScope passed through to both [CurrentUser] and
 *   [ProfileAwareCurrentUser]. Pass `backgroundScope` in jvmTest (auto-cancelled).
 *   Pass `createBackgroundScope()` in commonTest direct constructor calls —
 *   safe only when the consumer reads `.value` synchronously.
 */
fun FakeProfileAwareCurrentUser(
    initialUserId: UserId = UserId("test-user"),
    profileRepository: ProfileRepository = FakeProfileRepository(),
    scope: kotlinx.coroutines.CoroutineScope = com.singularity.todo.core.coroutines.createBackgroundScope(),
): ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(
    authRepository = FakeAuthRepository(Session.Anonymous(initialUserId)),
    profileRepository = profileRepository,
    scope = scope,
)

/**
 * Overload that accepts an existing [AuthRepository] — for call sites that already
 * have a [FakeAuthRepository] instance configured.
 *
 * Returns a [ProfileAwareCurrentUser] subclass. The `scopedUserId` is set directly
 * to `currentUser.userId` (bypassing the `combine(profileRepository.activeProfileId)`
 * step). This avoids the synchronous double-emission problem that `combine` produces
 * when both upstreams are already seeded with values: `combine` emits once per
 * upstream change, but its first emission can be lost if `stateIn` subscribes
 * before all upstreams have emitted. Using `currentUser.userId` directly is
 * correct for all test scenarios that exercise auth-session-driven user switches.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun FakeProfileAwareCurrentUser(
    authRepository: AuthRepository,
    profileRepository: ProfileRepository = FakeProfileRepository(),
    scope: kotlinx.coroutines.CoroutineScope = com.singularity.todo.core.coroutines.createBackgroundScope(),
): ProfileAwareCurrentUser {
    val currentUser = CurrentUser(authRepository, scope)

    // Build scopedUserId as a simple MutableStateFlow, seeded with the current
    // effective userId from the session. We monitor session changes in the scope
    // and update it reactively. This avoids the combine+stateIn synchronous
    // double-emission problem entirely.
    val initialUid = (authRepository.session.value.let {
        when (it) {
            is Session.SignedIn -> it.userId
            is Session.Anonymous -> it.userId
            else -> UserId.anonymous
        }
    })

    // Build scopedUserId from session changes via plain collect. The collector
    // is launched on the provided [scope] so when the test passes `backgroundScope`,
    // `advanceUntilIdle()` drives it via the TestDispatcher. UNDISPATCHED so the
    // collector subscribes to session synchronously (avoiding the TestDispatcher's
    // "schedule and return" semantics for the initial subscription).
    val fakeScopedUserId = MutableStateFlow(initialUid)
    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        authRepository.session.collect { session ->
            fakeScopedUserId.value = extractUserId(session)
        }
    }

    return object : ProfileAwareCurrentUser(currentUser, profileRepository, scope) {
        override val scopedUserId: kotlinx.coroutines.flow.StateFlow<UserId> = fakeScopedUserId.asStateFlow()
    }
}

private fun extractUserId(session: Session): UserId = when (session) {
    is Session.SignedIn -> session.userId
    is Session.Anonymous -> session.userId
    else -> UserId.anonymous
}

// ─── AttachmentSaver ──────────────────────────────────────────────────────────

/**
 * Fake [com.singularity.todo.feature.tasks.domain.model.AttachmentSaver] for tests.
 */
class FakeAttachmentSaver : com.singularity.todo.feature.tasks.domain.model.AttachmentSaver {
    val saved = mutableListOf<Triple<String, String, String?>>()

    override suspend fun save(taskId: TaskId, path: String, mimeType: String?): Result<Unit> = runCatching {
        saved.add(Triple(taskId.value, path, mimeType))
    }
}

// ─── SavedAgendaViewsRepository ────────────────────────────────────────────────

/**
 * Fake [SavedAgendaViewsRepository] backed by a reactive [MutableStateFlow].
 * Unlike the production [RoomSavedAgendaViewsRepository], this implementation
 * replays the current state on every subscription — suitable for unit tests.
 */
class FakeSavedAgendaViewsRepository(
    private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(),
) : SavedAgendaViewsRepository {

    private val store = MutableStateFlow<Map<SavedAgendaViewKey, SavedAgendaView>>(emptyMap())

    // ─── UserId-free observation (Phase 2 pattern) ───────────────────────────────

    override fun watchAllForCurrentUser(): Flow<List<SavedAgendaView>> =
        currentUser.observeForCurrentUser { uid ->
            store.map { map ->
                map.values.filter { it.userId == uid.value }.sortedBy { it.name }
            }
        }

    override fun watchByIdForCurrentUser(id: SavedAgendaViewId): Flow<SavedAgendaView?> =
        currentUser.observeForCurrentUser { uid ->
            store.map { map -> map[SavedAgendaViewKey.of(uid.value, id.raw)] }
        }

    override suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView> = runCatching {
        store.update { map -> map + (SavedAgendaViewKey.of(view.userId, view.id.raw) to view) }
        view
    }

    override suspend fun delete(id: SavedAgendaViewId): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        store.update { map -> map - SavedAgendaViewKey.of(uid.value, id.raw) }
    }

    /** Synchronous upsert for tests. */
    fun upsertSync(view: SavedAgendaView) {
        store.update { map -> map + (SavedAgendaViewKey.of(view.userId, view.id.raw) to view) }
    }

    /** Get a view by raw ID string for tests. */
    fun getById(id: String): SavedAgendaView? {
        return store.value.values.find { it.id.raw == id }
    }

    /** Get all views for tests. */
    fun getAll(): List<SavedAgendaView> = store.value.values.toList()

    /** Clear all views for test isolation. */
    fun clear() {
        store.value = emptyMap()
    }
}

/**
 * No-op [FileRevealer] for tests.
 */
class FakeFileRevealer : FileRevealer {
    override suspend fun revealAttachmentsFolder(folderPath: String) {
        // no-op in tests
    }

    override fun attachmentsBasePath(): String = "/fake/attachments"
}
