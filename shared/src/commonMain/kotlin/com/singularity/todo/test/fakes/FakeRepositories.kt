package com.singularity.todo.test.fakes

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupResult
import com.singularity.todo.core.backup.ExportOptions
import com.singularity.todo.core.backup.ImportOptions
import com.singularity.todo.core.backup.RestoreResult
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.ProjectWithCountRow
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.notifications.NotificationsSettingsRepository
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.schedule.GreetingSettingsRepository
import com.singularity.todo.core.schedule.WorkScheduleSettingsRepository
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.agenda.DefaultAgendaViewSettingsRepository
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewKey
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.ProfileRepository
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

// ─── SettingsRepository ────────────────────────────────────────────────────────

class FakeSettingsRepository(initialUserId: String = "test-user") : SettingsRepository {
    // ── Per-section repositories ──────────────────────────────────────────────

    override val notifications: NotificationsSettingsRepository =
        object : NotificationsSettingsRepository {
            private val _enabled = MutableStateFlow(true)
            private val _sound = MutableStateFlow(true)
            private val _vibration = MutableStateFlow(true)
            private val _reminderDefault = MutableStateFlow(ReminderOffset.AT_DUE)
            override val enabled: Flow<Boolean> = _enabled
            override val sound: Flow<Boolean> = _sound
            override val vibration: Flow<Boolean> = _vibration
            override val reminderDefault: Flow<ReminderOffset> = _reminderDefault
            override suspend fun setEnabled(value: Boolean) {
                _enabled.value = value
            }
            override suspend fun setSound(value: Boolean) {
                _sound.value = value
            }
            override suspend fun setVibration(value: Boolean) {
                _vibration.value = value
            }
            override suspend fun setReminderDefault(value: ReminderOffset) {
                _reminderDefault.value = value
            }
        }

    override val workSchedule: WorkScheduleSettingsRepository =
        object : WorkScheduleSettingsRepository {
            private val _dayStart = MutableStateFlow(540)
            private val _dayEnd = MutableStateFlow(1080)
            private val _lunchStart = MutableStateFlow(720)
            private val _lunchEnd = MutableStateFlow(780)
            private val _sat = MutableStateFlow(false)
            private val _sun = MutableStateFlow(false)
            override val dayStartMinutes: Flow<Int> = _dayStart
            override val dayEndMinutes: Flow<Int> = _dayEnd
            override val lunchStartMinutes: Flow<Int> = _lunchStart
            override val lunchEndMinutes: Flow<Int> = _lunchEnd
            override val weekendSat: Flow<Boolean> = _sat
            override val weekendSun: Flow<Boolean> = _sun
            override suspend fun setDayStartMinutes(value: Int) {
                _dayStart.value = value
            }
            override suspend fun setDayEndMinutes(value: Int) {
                _dayEnd.value = value
            }
            override suspend fun setLunchStartMinutes(value: Int) {
                _lunchStart.value = value
            }
            override suspend fun setLunchEndMinutes(value: Int) {
                _lunchEnd.value = value
            }
            override suspend fun setWeekendSat(value: Boolean) {
                _sat.value = value
            }
            override suspend fun setWeekendSun(value: Boolean) {
                _sun.value = value
            }
        }

    override val greeting: GreetingSettingsRepository =
        object : GreetingSettingsRepository {
            private val _morning = MutableStateFlow(12)
            private val _afternoon = MutableStateFlow(18)
            override val morningEndHour: Flow<Int> = _morning
            override val afternoonEndHour: Flow<Int> = _afternoon
            override suspend fun setMorningEndHour(hour: Int) {
                _morning.value = hour
            }
            override suspend fun setAfternoonEndHour(hour: Int) {
                _afternoon.value = hour
            }
        }

    override val defaultAgendaView: DefaultAgendaViewSettingsRepository =
        object : DefaultAgendaViewSettingsRepository {
            private val _id = MutableStateFlow<SavedAgendaViewId?>(null)
            override val defaultViewId: Flow<SavedAgendaViewId?> = _id
            override suspend fun setDefaultViewId(id: SavedAgendaViewId?) {
                _id.value = id
            }
        }

    // ── AI (flat — AiSettingsStore reads via SettingsReader) ────────────────

    private val _aiProvider = MutableStateFlow("openai")
    private val _aiModel = MutableStateFlow("gpt-4o-mini")
    private val _aiBaseUrl = MutableStateFlow("https://api.openai.com/v1")
    private val _aiSystemPrompt = MutableStateFlow(SettingsRepository.DEFAULT_SYSTEM_PROMPT)

    override val aiProvider: Flow<String> = _aiProvider
    override val aiModel: Flow<String> = _aiModel
    override val aiBaseUrl: Flow<String> = _aiBaseUrl
    override val aiSystemPrompt: Flow<String> = _aiSystemPrompt

    // ── Account ─────────────────────────────────────────────────────────────

    private val _userId = MutableStateFlow(initialUserId)
    override val userId: Flow<String> = _userId

    // ── Setters ───────────────────────────────────────────────────────────────

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
    override suspend fun setUserId(value: String) {
        _userId.value = value
    }
}

// ─── BackupRepository ─────────────────────────────────────────────────────────

class FakeBackupRepository : BackupRepository {
    private val backupsStore = MutableStateFlow<List<BackupMetadata>>(emptyList())
    override fun observeAll(): Flow<List<BackupMetadata>> = backupsStore

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
        backupsStore.value += backup
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
        backupsStore.value = backupsStore.value.filter { it.id != backupId }
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
internal class InMemoryTaskDao : TaskDao {
    private val _deps = MutableStateFlow<List<TaskDependencyCrossRef>>(emptyList())
    private val _tags = MutableStateFlow<List<TaskTagCrossRef>>(emptyList())

    // ── Dependency methods (the only ones used by FakeTaskRepository) ─────────

    override fun getDependencyIdsForTask(taskId: String): Flow<List<String>> =
        _deps.map { refs -> refs.filter { it.taskId == taskId }.map { it.dependsOnTaskId } }

    override fun getBlockingTaskIdsForTask(taskId: String): Flow<List<String>> =
        _deps.map { refs -> refs.filter { it.dependsOnTaskId == taskId }.map { it.taskId } }

    override suspend fun upsertDependency(ref: TaskDependencyCrossRef) {
        _deps.update { current ->
            current.filter {
                !(
                    it.taskId == ref.taskId &&
                        it.dependsOnTaskId == ref.dependsOnTaskId
                )
            } +
                ref
        }
    }

    // Ownership-scoped variants. This stub has no task store, so the userId check
    // cannot be evaluated — it applies the mutation and reports the affected count,
    // preserving the pre-existing behaviour for the dependency tests.
    override suspend fun removeDependencyForUser(taskId: String, depId: String, userId: String): Int {
        val before = _deps.value.count { it.taskId == taskId && it.dependsOnTaskId == depId }
        _deps.update { current -> current.filter { !(it.taskId == taskId && it.dependsOnTaskId == depId) } }
        return before
    }

    override suspend fun clearDependenciesForUser(taskId: String, userId: String): Int {
        val before = _deps.value.count { it.taskId == taskId }
        _deps.update { current -> current.filter { it.taskId != taskId } }
        return before
    }

    override suspend fun upsertDependencyForUser(taskId: String, depId: String, userId: String) {
        upsertDependency(TaskDependencyCrossRef(taskId = taskId, dependsOnTaskId = depId))
    }

    // ── Tag methods (stubs so the interface is satisfied) ─────────────────────

    override fun getTagIdsForTask(taskId: String): Flow<List<String>> =
        _tags.map { refs -> refs.filter { it.taskId == taskId }.map { it.tagId } }

    override suspend fun upsertTagCrossRef(ref: TaskTagCrossRef) {
        _tags.update { current -> current.filter { !(it.taskId == ref.taskId && it.tagId == ref.tagId) } + ref }
    }

    override suspend fun upsertTagCrossRefForUser(taskId: String, tagId: String, userId: String) {
        upsertTagCrossRef(TaskTagCrossRef(taskId = taskId, tagId = tagId))
    }

    override suspend fun removeTagRefForUser(taskId: String, tagId: String, userId: String): Int {
        val before = _tags.value.count { it.taskId == taskId && it.tagId == tagId }
        _tags.update { current -> current.filter { !(it.taskId == taskId && it.tagId == tagId) } }
        return before
    }

    // ── Batch extras (for TaskRepositoryImpl userTasksWithExtras) ─────────────

    override fun observeTagCrossRefs(userId: String): Flow<List<TaskTagCrossRef>> = _tags

    override fun observeDependencyCrossRefs(userId: String): Flow<List<TaskDependencyCrossRef>> = _deps

    // ── Outgoing links (stub — not used by FakeTaskRepository) ───────────────

    override suspend fun setOutgoingLinksForUser(id: String, linksJson: String, updatedAt: Long, userId: String): Int {
        // no-op: FakeTaskRepository does not use this path
        return 0
    }

    override suspend fun getBacklinkTasks(
        taskId: String,
        userId: String,
    ): List<com.singularity.todo.core.database.TaskEntity> = error("not implemented")

    // ── Remaining DAO methods (unused by FakeTaskRepository) ──────────────────

    override fun watchActive(userId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchById(id: String): Flow<com.singularity.todo.core.database.TaskEntity?> = error("not implemented")

    override fun watchTrash(userId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override suspend fun getTrashForUser(userId: String): List<com.singularity.todo.core.database.TaskEntity> =
        error("not implemented")

    override fun watchSomeday(userId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByDate(userId: String, date: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchUpcoming(
        userId: String,
        today: String,
        endDate: String,
    ): Flow<List<com.singularity.todo.core.database.TaskEntity>> = error("not implemented")

    override fun watchByProject(
        userId: String,
        projectId: String,
    ): Flow<List<com.singularity.todo.core.database.TaskEntity>> = error("not implemented")

    override fun watchByDateRange(
        userId: String,
        from: String,
        to: String,
    ): Flow<List<com.singularity.todo.core.database.TaskEntity>> = error("not implemented")

    override fun watchByTag(userId: String, tagId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override fun watchByAnyTag(
        userId: String,
        tagIds: List<String>,
    ): Flow<List<com.singularity.todo.core.database.TaskEntity>> = error("not implemented")

    override fun watchByAllTags(
        userId: String,
        tagIds: List<String>,
        size: Int,
    ): Flow<List<com.singularity.todo.core.database.TaskEntity>> = error("not implemented")

    override fun watchByPriorities(
        userId: String,
        priorities: List<String>,
    ): Flow<List<com.singularity.todo.core.database.TaskEntity>> = error("not implemented")

    override fun watchByRegexp(
        userId: String,
        pattern: String,
    ): Flow<List<com.singularity.todo.core.database.TaskEntity>> = error("not implemented")

    override fun watchPinned(userId: String): Flow<List<com.singularity.todo.core.database.TaskEntity>> =
        error("not implemented")

    override suspend fun setPinnedForUser(id: String, pinned: Boolean, ts: Long, userId: String): Int =
        error("not implemented")

    override suspend fun getById(id: String): com.singularity.todo.core.database.TaskEntity? = error("not implemented")

    override fun watchSearchResults(
        userId: String,
        q: String,
    ): Flow<List<com.singularity.todo.core.database.TaskEntity>> = error(
        "not implemented",
    )

    override suspend fun searchTitles(userId: String, q: String): List<com.singularity.todo.core.database.TaskEntity> =
        error(
            "not implemented",
        )

    override suspend fun upsert(task: com.singularity.todo.core.database.TaskEntity) = error("not implemented")

    override suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int = error("not implemented")

    override suspend fun restoreForUser(id: String, ts: Long, userId: String): Int = error("not implemented")

    override suspend fun markCompleteForUser(id: String, ts: Long, userId: String): Int = error("not implemented")

    override suspend fun markIncompleteForUser(id: String, ts: Long, userId: String): Int = error("not implemented")

    override suspend fun listAllForUser(userId: String): List<com.singularity.todo.core.database.TaskEntity> = error(
        "not implemented",
    )

    override suspend fun listAllDependenciesForUser(userId: String): List<TaskDependencyCrossRef> = _deps.value

    override suspend fun listAllTagsForUser(userId: String): List<TaskTagCrossRef> = error("not implemented")

    override suspend fun archiveCompletedForUser(ts: Long, userId: String): Int = error("not implemented")
}

open class FakeTaskRepository(
    private val dao: TaskDao = InMemoryTaskDao(),
    private val explicitCurrentUser: ProfileAwareCurrentUser? = null,
) : TaskRepository {
    private val store = InMemoryStore<Task>(keyOf = { it.id.value })

    // The effective currentUser — injected for tests, or a default fake for backward compatibility.
    private val currentUser: ProfileAwareCurrentUser
        get() = explicitCurrentUser ?: FakeProfileAwareCurrentUser()

    /** Expose store state as [StateFlow] for [watchTasks] and other flows. */
    internal val tasks: StateFlow<Map<String, Task>> = store.state

    // ─── Configurable results (for failure-path tests) ────────────────────
    var createOverride: Result<Task>? = null
    var updateOverride: Result<Task>? = null
    var deleteOverride: Result<Unit>? = null
    var softDeleteOverride: Result<Unit>? = null
    var restoreOverride: Result<Unit>? = null
    var toggleCompleteOverride: Result<Unit>? = null
    var togglePinnedOverride: Result<Unit>? = null
    var setTagsOverride: Result<Unit>? = null
    var setDependenciesOverride: Result<Unit>? = null

    /** Seeds tasks by merging into existing state (adds or overwrites by id). */
    fun seed(vararg tasks: Task) = store.seed(tasks.toList())

    fun add(task: Task) = store.upsert(task)

    fun clear() = store.clear()

    // ── GenericUserScopedRepository implementation ────────────────────────────────

    override suspend fun currentUserId(): UserId = currentUser.scopedUserId.value

    override fun observeAll(): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        store.state
            .onStart { emit(store.state.value) }
            .map { map -> map.values.filter { it.userId == uid }.toList() }
    }

    override fun observe(id: TaskId): Flow<Task?> = store.state.onStart { emit(store.state.value) }.map { it[id.value] }

    open override suspend fun create(item: Task): Result<Task> {
        createOverride?.let { return it }
        return runCatching {
            store.upsert(item)
            item
        }
    }

    open override suspend fun update(item: Task): Result<Task> {
        updateOverride?.let { return it }
        return runCatching {
            // Production reads before writing and rejects updates to entities
            // that do not exist. The fake accepted them, so a test asserting
            // that behaviour could never fail here.
            val uid = currentUserId()
            store[item.id.value]?.takeIf { it.userId == uid }
                ?: throw IllegalArgumentException("Task not found or not owned: ${item.id.value}")
            store.upsert(item)
            item
        }
    }

    open override suspend fun delete(id: TaskId): Result<Unit> {
        deleteOverride?.let { return it }
        return runCatching {
            // Production `delete` soft-deletes (sets archivedAt) and keeps the row.
            // The fake hard-deleted it, so a test asserting "the task is in the
            // trash" or "deleting twice fails" could not pass against the fake.
            val uid = currentUserId()
            val existing = store[id.value]?.takeIf { it.userId == uid }
                ?: throw IllegalArgumentException("Task not found or not owned: ${id.value}")
            store.upsert(existing.copy(archivedAt = kotlin.time.Clock.System.now()))
        }
    }

    override suspend fun upsert(task: Task): Task {
        store.upsert(task)
        return task
    }

    // ── Domain-specific user-scoped observers ────────────────────────────────────

    override fun observeByFilter(filter: TaskFilter): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
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
                                Clock.System.now().toEpochMilliseconds(),
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

    override fun observeSubtasks(parentId: TaskId): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        store.state
            .onStart { emit(store.state.value) }
            .map { map -> map.values.filter { it.parentTaskId == parentId && it.userId == uid } }
    }

    override fun observeDependencies(taskId: TaskId): Flow<Set<TaskId>> =
        dao.getDependencyIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    override fun observeBlockingBy(taskId: TaskId): Flow<Set<TaskId>> =
        dao.getBlockingTaskIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    // ── Legacy / DAO-backed methods ─────────────────────────────────────────────

    open override suspend fun softDelete(id: TaskId): Result<Unit> {
        softDeleteOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { task ->
                val deleted = task.copy(archivedAt = Clock.System.now())
                store.upsert(deleted)
            }
        }
    }

    override suspend fun get(id: TaskId): Task? = store[id.value]

    // getByIdForCurrentUser intentionally omitted — use getById + caller-side userId check

    open override suspend fun restore(id: TaskId): Result<Unit> {
        restoreOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { task ->
                val restored = task.copy(archivedAt = null)
                store.upsert(restored)
            }
        }
    }

    open override suspend fun toggleComplete(id: TaskId): Result<Unit> {
        toggleCompleteOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { task ->
                val toggled = if (task.completedAt != null) {
                    task.copy(completedAt = null)
                } else {
                    task.copy(completedAt = Clock.System.now())
                }
                store.upsert(toggled)
            }
        }
    }

    open override suspend fun togglePinned(id: TaskId): Result<Unit> {
        togglePinnedOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { task ->
                val toggled = task.copy(isPinned = !task.isPinned)
                store.upsert(toggled)
            }
        }
    }

    override suspend fun exists(id: TaskId): Boolean = store.contains(id.value)

    open override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> {
        setTagsOverride?.let { return it }
        return runCatching {
            store[taskId.value]?.let { task ->
                val updated = task.copy(tags = tagIds)
                store.upsert(updated)
            }
        }
    }

    override fun getTagIds(taskId: TaskId): Flow<List<TagId>> =
        store.state.map { it[taskId.value]?.tags ?: emptyList() }

    open override suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit> {
        setDependenciesOverride?.let { return it }
        return runCatching {
            val uid = currentUserId().value
            dao.clearDependenciesForUser(taskId.value, uid)
            deps.forEach { dep ->
                dao.upsertDependencyForUser(taskId.value, dep.value, uid)
            }
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

    override suspend fun addItem(taskId: String, title: String): Result<ChecklistItemId> = runCatching {
        val item = ChecklistItem(
            id = ChecklistItemId.generate(),
            taskId = taskId,
            title = title,
            isCompleted = false,
            sortOrder = 0,
        )
        items.value += (item.id.value to item)
        item.id
    }

    override suspend fun toggleItem(taskId: String, itemId: ChecklistItemId): Result<Unit> = runCatching {
        val current = items.value.values.firstOrNull { it.id == itemId && it.taskId == taskId }
            ?: throw IllegalArgumentException("Checklist item not found: $itemId")
        items.value += (itemId.value to current.copy(isCompleted = !current.isCompleted))
    }

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

open class FakeReminderRepository(private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser()) :
    ReminderRepository {
    internal val reminders = MutableStateFlow<Map<String, Reminder>>(emptyMap())

    fun seed(vararg reminders: Reminder) {
        this.reminders.value = reminders.associateBy { it.id.value }
    }

    // ─── Configurable results (for failure-path tests) ────────────────────
    var upsertOverride: Result<Unit>? = null
    var deleteOverride: Result<Unit>? = null
    var deleteWithUserIdOverride: Result<Unit>? = null
    var deleteByTaskOverride: Result<Unit>? = null
    var markFiredOverride: Result<Unit>? = null

    // ─── Generic CRUD (ambient user) ─────────────────────────────────────────

    override fun observeAll(): Flow<List<Reminder>> = currentUser.observeForCurrentUser { uid ->
        reminders.map { map -> map.values.filter { it.userId == uid }.sortedBy { it.fireAt } }
    }

    override fun observe(id: ReminderId): Flow<Reminder?> = currentUser.observeForCurrentUser { uid ->
        reminders.map { map -> map.values.firstOrNull { it.id == id && it.userId == uid } }
    }

    override suspend fun get(id: ReminderId): Reminder? {
        val uid = currentUser.scopedUserId.value
        return reminders.value.values.firstOrNull { it.id == id && it.userId == uid }
    }

    open override suspend fun upsert(reminder: Reminder): Result<Unit> {
        upsertOverride?.let { return it }
        return runCatching {
            reminders.value += (reminder.id.value to reminder)
        }
    }

    open override suspend fun delete(id: ReminderId): Result<Unit> {
        deleteOverride?.let { return it }
        return runCatching {
            reminders.value = reminders.value.filterKeys { it != id.value }
        }
    }

    open override suspend fun delete(id: ReminderId, userId: com.singularity.todo.core.ids.UserId): Result<Unit> {
        deleteWithUserIdOverride?.let { return it }
        return runCatching {
            reminders.value = reminders.value.filterKeys { it != id.value }
        }
    }

    // ─── Domain methods ─────────────────────────────────────────────────────

    override fun observeRecurringTaskIds(): Flow<Set<TaskId>> = currentUser.observeForCurrentUser { uid ->
        reminders.map { map ->
            map.values
                .filter { it.userId == uid && it.recurringPattern != null }
                .mapTo(mutableSetOf()) { it.taskId }
        }
    }

    override fun watchByTask(taskId: TaskId): Flow<List<Reminder>> = currentUser.observeForCurrentUser { uid ->
        reminders.map { map ->
            map.values.filter { it.taskId == taskId && it.userId == uid }.sortedBy { it.fireAt }
        }
    }

    override fun watchDueBefore(nowEpochMs: Long): Flow<List<Reminder>> = currentUser.observeForCurrentUser { uid ->
        reminders.map { map ->
            map.values.filter { it.fireAt <= nowEpochMs && it.userId == uid }.sortedBy { it.fireAt }
        }
    }

    override fun watchRecentDueBefore(nowEpochMs: Long, limit: Int): Flow<List<Reminder>> =
        currentUser.observeForCurrentUser { uid ->
            reminders.map { map ->
                map.values
                    .filter { it.fireAt <= nowEpochMs && it.userId == uid }
                    .sortedBy { r -> -r.fireAt }
                    .take(limit)
            }
        }

    open override suspend fun deleteByTask(taskId: TaskId): Result<Unit> {
        deleteByTaskOverride?.let { return it }
        return runCatching {
            reminders.value = reminders.value.filterValues { it.taskId != taskId }
        }
    }

    open override suspend fun markFired(reminderId: ReminderId, lastFiredAt: Long): Result<Unit> {
        markFiredOverride?.let { return it }
        return runCatching {
            val existing = reminders.value[reminderId.value] ?: return@runCatching
            reminders.value += (reminderId.value to existing.copy(lastFiredAt = lastFiredAt))
        }
    }
}

// ─── AuthRepository ───────────────────────────────────────────────────────────

class FakeAuthRepository(initialSession: Session = Session.Anonymous(UserId.anonymous)) : AuthRepository {
    private val _currentSession = MutableStateFlow(initialSession)

    // Return _currentSession directly — MutableStateFlow IS a StateFlow, so this satisfies
    // the interface. Using asStateFlow() creates a new wrapper each call, which
    // breaks shared subscription state between callers.
    override val currentSession: StateFlow<Session> = _currentSession

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    override suspend fun signUp(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signIn(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInAnonymously(): Result<Unit> = Result.success(Unit)
    override suspend fun signOut(): Result<Unit> = runCatching {
        _currentSession.value = Session.SignedOut
    }

    override suspend fun migrateAnonymousTo(newUserId: UserId): Result<Unit> = Result.success(Unit)

    /**
     * Switches the session to a new anonymous user with [userId].
     * For use in tests that simulate profile/user switches.
     */
    fun setUserId(userId: UserId) {
        _currentSession.value = Session.Anonymous(userId)
    }
}

// ─── ProjectsRepository ──────────────────────────────────────────────────────

class FakeProjectsRepository(private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser()) :
    ProjectsRepository {
    internal val store = InMemoryStore<Project>(
        keyOf = { it.id.value },
    )

    fun seed(vararg projects: Project) = store.seed(projects.toList())
    fun add(project: Project) = store.upsert(project)
    fun clear() = store.clear()

    // ─── GenericUserScopedRepository ──────────────────────────────────────────

    override fun observeAll(): Flow<List<Project>> = currentUser.observeForCurrentUser { uid ->
        store.state.map { list -> list.values.filter { it.userId == uid && !it.isDeleted } }
    }

    override fun observe(id: ProjectId): Flow<Project?> = currentUser.observeForCurrentUser { uid ->
        store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
    }

    override suspend fun get(id: ProjectId): Project? {
        val uid = currentUser.scopedUserId.value
        return store[id.value]?.takeIf { it.userId == uid }
    }

    /**
     * Scoped to the current user, matching production's `watchByIdForUser`.
     * Previously this filtered by nothing, so it returned another user's project
     * and any test asserting cross-user isolation passed vacuously.
     */
    override fun observeProject(id: ProjectId): Flow<Project?> = currentUser.observeForCurrentUser { uid ->
        store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
    }

    override suspend fun create(item: Project): Result<Project> = runCatching {
        store.upsert(item)
        item
    }

    override suspend fun update(item: Project): Result<Project> = runCatching {
        store.upsert(item)
        item
    }

    override suspend fun delete(id: ProjectId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(isDeleted = true, deletedAt = Clock.System.now()))
        }
    }

    override suspend fun upsert(project: Project): Project {
        store.upsert(project)
        return project
    }

    // ─── SoftDeletable ───────────────────────────────────────────────────────

    override suspend fun restore(id: ProjectId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(isDeleted = false, deletedAt = null))
        }
    }

    // ─── Domain methods ─────────────────────────────────────────────────────

    override fun observeProjectsWithCounts(): Flow<List<ProjectWithCountRow>> =
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

    override fun observeChildrenOf(parentId: ProjectId): Flow<List<Project>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values.filter { it.parentId == parentId && it.userId == uid && !it.isDeleted }
            }
        }

    /** Scoped to the current user, matching production's `watchByParentForUser`. */
    override fun observeByParent(parentId: ProjectId): Flow<List<Project>> = currentUser.observeForCurrentUser { uid ->
        store.state.map { list ->
            list.values.filter { it.parentId == parentId && it.userId == uid && !it.isDeleted }
        }
    }

    /** Scoped to the current user, matching production's `watchByIdForUser`. */
    override fun changes(id: ProjectId): Flow<Project?> = currentUser.observeForCurrentUser { uid ->
        store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
    }

    override suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long) {
        val uid = currentUser.scopedUserId.value
        store[id.value]?.takeIf { it.userId == uid }?.let { existing ->
            store.upsert(
                existing.copy(parentId = parentId, updatedAt = kotlin.time.Instant.fromEpochMilliseconds(updatedAt)),
            )
        }
    }

    override suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long) {
        val uid = currentUser.scopedUserId.value
        store[id.value]?.takeIf { it.userId == uid }?.let { existing ->
            store.upsert(
                existing.copy(sortOrder = sortOrder, updatedAt = kotlin.time.Instant.fromEpochMilliseconds(updatedAt)),
            )
        }
    }

    /** Scoped to the current user, matching production's `findByIdempotencyKeyForUser`. */
    override suspend fun findByIdempotencyKey(key: String): Project? {
        val uid = currentUser.scopedUserId.value
        return store.values().firstOrNull { it.idempotencyKey == key && it.userId == uid }
    }
}

// ─── TagsRepository ──────────────────────────────────────────────────────────

class FakeTagsRepository(private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser()) :
    com.singularity.todo.feature.tags.TagsRepository {
    private val store = InMemoryStore<com.singularity.todo.feature.tags.Tag>(keyOf = { it.id.value })

    fun seed(vararg tags: com.singularity.todo.feature.tags.Tag) = store.seed(tags.toList())
    fun add(tag: com.singularity.todo.feature.tags.Tag) = store.upsert(tag)
    fun clear() = store.clear()

    // ─── GenericUserScopedRepository ──────────────────────────────────────────

    override fun observeAll(): Flow<List<com.singularity.todo.feature.tags.Tag>> =
        currentUser.observeForCurrentUser { uid ->
            store.state
                .onStart { emit(store.state.value) }
                .map { list -> list.values.filter { it.userId == uid } }
        }

    /** Scoped to the current user, matching production's `watchByIdForUser`. */
    override fun observe(id: TagId): Flow<com.singularity.todo.feature.tags.Tag?> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
        }

    override suspend fun get(id: TagId): com.singularity.todo.feature.tags.Tag? {
        val uid = currentUser.scopedUserId.value
        return store[id.value]?.takeIf { it.userId == uid }
    }

    override suspend fun create(
        tag: com.singularity.todo.feature.tags.Tag,
    ): Result<com.singularity.todo.feature.tags.Tag> = runCatching {
        store.upsert(tag)
        tag
    }

    override suspend fun update(
        tag: com.singularity.todo.feature.tags.Tag,
    ): Result<com.singularity.todo.feature.tags.Tag> = runCatching {
        store.upsert(tag)
        tag
    }

    /** Scoped to the current user, matching production's `watchByIdForUser`. */
    override fun observeTag(id: TagId): Flow<com.singularity.todo.feature.tags.Tag?> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
        }

    override suspend fun delete(id: TagId): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        val existing = store[id.value]?.takeIf { it.userId == uid }
            ?: throw NoSuchElementException("Tag $id not found or not owned by current user")
        // Previously stamped deletedAt = epoch(0) rather than "now", so the tag
        // looked trashed since 1970 — anything comparing the timestamp saw a
        // different value than production produces.
        store.upsert(existing.copy(deletedAt = kotlin.time.Clock.System.now()))
    }

    override suspend fun upsert(tag: com.singularity.todo.feature.tags.Tag): com.singularity.todo.feature.tags.Tag {
        store.upsert(tag)
        return tag
    }
}

// ─── AttachmentRepository ────────────────────────────────────────────────────

open class FakeAttachmentRepository(private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser()) :
    com.singularity.todo.core.attachments.AttachmentRepository {
    private val store = InMemoryStore<com.singularity.todo.core.attachments.Attachment>(keyOf = { it.id.value })

    fun seed(vararg attachments: com.singularity.todo.core.attachments.Attachment) = store.seed(attachments.toList())

    // ─── Configurable results (for failure-path tests) ────────────────────
    var createOverride: Result<com.singularity.todo.core.attachments.Attachment>? = null
    var deleteOverride: Result<Unit>? = null
    var saveFileAttachmentOverride: Result<com.singularity.todo.core.attachments.Attachment>? = null
    var addUrlAttachmentOverride: Result<com.singularity.todo.core.attachments.Attachment>? = null

    // ─── Generic CRUD (ambient user) ─────────────────────────────────────────

    override fun observeAll(): Flow<List<com.singularity.todo.core.attachments.Attachment>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.filter { it.userId == uid } }
        }

    override fun observe(
        id: com.singularity.todo.core.attachments.AttachmentId,
    ): Flow<com.singularity.todo.core.attachments.Attachment?> = currentUser.observeForCurrentUser { uid ->
        store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
    }

    override suspend fun get(
        id: com.singularity.todo.core.attachments.AttachmentId,
    ): com.singularity.todo.core.attachments.Attachment? {
        val uid = currentUser.scopedUserId.value
        return store.state.value.values.firstOrNull { it.id == id && it.userId == uid }
    }

    open override suspend fun create(
        attachment: com.singularity.todo.core.attachments.Attachment,
    ): Result<com.singularity.todo.core.attachments.Attachment> {
        createOverride?.let { return it }
        return runCatching {
            store.upsert(attachment)
            attachment
        }
    }

    open override suspend fun delete(id: com.singularity.todo.core.attachments.AttachmentId): Result<Unit> {
        deleteOverride?.let { return it }
        return runCatching {
            store.remove(id.value)
        }
    }

    // ─── Domain methods ─────────────────────────────────────────────────────

    override fun watchByTask(taskId: TaskId): Flow<List<com.singularity.todo.core.attachments.Attachment>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.filter { it.taskId == taskId && it.userId == uid } }
        }

    // ─── Domain methods (ambient user) ────────────────────────────────────

    open override suspend fun saveFileAttachment(
        taskId: TaskId,
        sourcePath: String,
        mimeType: String?,
    ): Result<com.singularity.todo.core.attachments.Attachment> {
        saveFileAttachmentOverride?.let { return it }
        return runCatching {
            val uid = currentUser.scopedUserId.value
            // `checksum` / `fileSizeBytes` stay at their defaults. Production
            // computes them from the file via the AttachmentStorage port, which
            // this fake has no access to; inventing values here would make a test
            // assert against a checksum that could never match production.
            // Exercising the checksum path needs a storage double.
            val att = com.singularity.todo.core.attachments.Attachment(
                id = com.singularity.todo.core.attachments.AttachmentId.generate(),
                taskId = taskId,
                userId = uid,
                type = com.singularity.todo.core.attachments.AttachmentType.File,
                localPath = sourcePath,
                mimeType = mimeType,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            )
            store.upsert(att)
            att
        }
    }

    open override suspend fun addUrlAttachment(
        taskId: TaskId,
        url: String,
        title: String?,
    ): Result<com.singularity.todo.core.attachments.Attachment> {
        addUrlAttachmentOverride?.let { return it }
        return runCatching {
            // Production rejects malformed URLs before writing; the fake accepted
            // anything, so validation could never be exercised in a test.
            com.singularity.todo.core.attachments.AttachmentDomain.validateUrl(url).getOrThrow()
            val uid = currentUser.scopedUserId.value
            val att = com.singularity.todo.core.attachments.Attachment(
                id = com.singularity.todo.core.attachments.AttachmentId.generate(),
                taskId = taskId,
                userId = uid,
                type = com.singularity.todo.core.attachments.AttachmentType.Url,
                url = url,
                title = title ?: "",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            )
            store.upsert(att)
            att
        }
    }
}

// ─── NotesRepository ─────────────────────────────────────────────────────────

open class FakeNotesRepository(private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser()) :
    com.singularity.todo.feature.notes.NotesRepository {
    /** Exposes raw store map for tests that need direct map access. */
    val notes: Map<String, com.singularity.todo.feature.notes.Note> get() = store.state.value
    private val store = InMemoryStore<com.singularity.todo.feature.notes.Note>(keyOf = { it.id.value })

    fun seed(note: com.singularity.todo.feature.notes.Note) = store.upsert(note)
    fun add(note: com.singularity.todo.feature.notes.Note) = store.upsert(note)
    fun clear() = store.clear()

    // ─── Configurable results (for failure-path tests) ────────────────────
    var createOverride: Result<com.singularity.todo.feature.notes.Note>? = null
    var updateOverride: Result<com.singularity.todo.feature.notes.Note>? = null
    var deleteOverride: Result<Unit>? = null
    var restoreOverride: Result<Unit>? = null
    var createWithContentOverride: Result<com.singularity.todo.feature.notes.NoteId>? = null
    var createNoteWithTitleOverride: Result<com.singularity.todo.feature.notes.NoteId>? = null
    var updateContentOverride: Result<Unit>? = null
    var archiveOverride: Result<Unit>? = null
    var unarchiveOverride: Result<Unit>? = null
    var setPinnedOverride: Result<Unit>? = null
    var setColorOverride: Result<Unit>? = null
    var setSortOrderOverride: Result<Unit>? = null
    var setOutgoingLinksOverride: Result<Unit>? = null

    // ─── GenericUserScopedRepository ──────────────────────────────────────────

    override fun observeAll(): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.filter { it.userId == uid && it.deletedAt == null } }
        }

    override fun observe(
        id: com.singularity.todo.feature.notes.NoteId,
    ): Flow<com.singularity.todo.feature.notes.Note?> = currentUser.observeForCurrentUser { uid ->
        store.state.map { list -> list.values.firstOrNull { it.id == id && it.userId == uid } }
    }

    override suspend fun get(id: com.singularity.todo.feature.notes.NoteId): com.singularity.todo.feature.notes.Note? {
        val uid = currentUser.scopedUserId.value
        return store.state.value.values.firstOrNull { it.id == id && it.userId == uid }
    }

    open override suspend fun create(
        item: com.singularity.todo.feature.notes.Note,
    ): Result<com.singularity.todo.feature.notes.Note> {
        createOverride?.let { return it }
        return runCatching {
            store.upsert(item)
            item
        }
    }

    open override suspend fun update(
        item: com.singularity.todo.feature.notes.Note,
    ): Result<com.singularity.todo.feature.notes.Note> {
        updateOverride?.let { return it }
        return runCatching {
            store.upsert(item)
            item
        }
    }

    open override suspend fun upsert(
        note: com.singularity.todo.feature.notes.Note,
    ): com.singularity.todo.feature.notes.Note = runCatching {
        store.upsert(note)
        note
    }.getOrThrow()

    open override suspend fun delete(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> {
        deleteOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(deletedAt = Clock.System.now()))
            }
        }
    }

    // ─── SoftDeletable ─────────────────────────────────────────────────────

    // ─── SoftDeletable ─────────────────────────────────────────────────────

    open override suspend fun restore(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> {
        restoreOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(deletedAt = null))
            }
        }
    }

    // ─── Domain methods ─────────────────────────────────────────────────────

    override fun watchPinned(): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list -> list.values.filter { it.userId == uid && it.isPinned && it.deletedAt == null } }
        }

    override fun watchArchived(): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values.filter { it.userId == uid && it.archivedAt != null && it.deletedAt == null }
            }
        }

    override fun watchRootNotes(): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values.filter {
                    it.userId == uid && it.parentNoteId == null && !it.isFolder && it.deletedAt == null
                }
            }
        }

    override fun search(query: String): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                // Title only, matching production's `watchSearchByTitle`
                // (`title LIKE ?`). The fake also matched body text, so a test
                // seeded with body content would find a note here that production
                // never returns. The fake is the test double, not a better spec —
                // whether note search *should* cover bodies is a product question.
                list.values.filter { note ->
                    note.userId == uid && note.deletedAt == null &&
                        note.title.contains(query, ignoreCase = true)
                }
            }
        }

    open override suspend fun createWithContent(
        id: com.singularity.todo.feature.notes.NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<com.singularity.todo.feature.notes.NoteId> {
        createWithContentOverride?.let { return it }
        return runCatching {
            val uid = currentUser.scopedUserId.value
            val now = Clock.System.now()
            val note = com.singularity.todo.feature.notes.Note(
                id = id,
                userId = uid,
                title = title,
                bodyMarkdown = bodyMarkdown,
                bodyHtml = bodyHtml,
                kind = com.singularity.todo.feature.notes.NoteKind.Plain,
                wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() },
                charCount = bodyMarkdown.length,
                createdAt = now,
                updatedAt = now,
            )
            store.upsert(note)
            id
        }
    }

    open override suspend fun createNoteWithTitle(title: String): Result<com.singularity.todo.feature.notes.NoteId> {
        createNoteWithTitleOverride?.let { return it }
        return runCatching {
            val uid = currentUser.scopedUserId.value
            val id = com.singularity.todo.feature.notes.NoteId(com.singularity.todo.core.ids.nextId())
            val now = Clock.System.now()
            val note = com.singularity.todo.feature.notes.Note(
                id = id,
                userId = uid,
                title = title,
                bodyMarkdown = null,
                bodyHtml = null,
                kind = com.singularity.todo.feature.notes.NoteKind.Plain,
                wordCount = 0,
                charCount = 0,
                createdAt = now,
                updatedAt = now,
            )
            store.upsert(note)
            id
        }
    }

    open override suspend fun updateContent(
        id: com.singularity.todo.feature.notes.NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<Unit> {
        updateContentOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { existing ->
                store.upsert(
                    existing.copy(
                        title = title,
                        bodyMarkdown = bodyMarkdown,
                        bodyHtml = bodyHtml,
                        wordCount = bodyMarkdown.split(Regex("\\s+")).count { it.isNotBlank() },
                        charCount = bodyMarkdown.length,
                        updatedAt = Clock.System.now(),
                    ),
                )
            }
        }
    }

    open override suspend fun archive(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> {
        archiveOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(archivedAt = Clock.System.now()))
            }
        }
    }

    open override suspend fun unarchive(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> {
        unarchiveOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(archivedAt = null))
            }
        }
    }

    open override suspend fun setPinned(id: com.singularity.todo.feature.notes.NoteId, pinned: Boolean): Result<Unit> {
        setPinnedOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(isPinned = pinned, pinnedAt = if (pinned) Clock.System.now() else null))
            }
        }
    }

    open override suspend fun setColor(
        id: com.singularity.todo.feature.notes.NoteId,
        color: com.singularity.todo.feature.notes.NoteColor?,
    ): Result<Unit> {
        setColorOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(color = color))
            }
        }
    }

    open override suspend fun setSortOrder(
        id: com.singularity.todo.feature.notes.NoteId,
        sortOrder: Int,
    ): Result<Unit> {
        setSortOrderOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(sortOrder = sortOrder))
            }
        }
    }

    open override suspend fun setOutgoingLinks(
        id: com.singularity.todo.feature.notes.NoteId,
        links: List<String>,
    ): Result<Unit> {
        setOutgoingLinksOverride?.let { return it }
        return runCatching {
            store[id.value]?.let { existing ->
                store.upsert(existing.copy(outgoingLinks = links))
            }
        }
    }

    // ─── Templates and daily notes ────────────────────────────────────────────────

    override fun watchTemplates(): Flow<List<com.singularity.todo.feature.notes.Note>> =
        currentUser.observeForCurrentUser { uid ->
            store.state.map { list ->
                list.values.filter {
                    it.userId == uid && it.kind == com.singularity.todo.feature.notes.NoteKind.Template &&
                        it.deletedAt == null
                }
            }
        }

    override fun watchDailyNotesInRange(
        from: String,
        to: String,
    ): Flow<List<com.singularity.todo.feature.notes.Note>> = currentUser.observeForCurrentUser { uid ->
        store.state.map { list ->
            list.values.filter {
                it.userId == uid &&
                    it.kind == com.singularity.todo.feature.notes.NoteKind.Daily &&
                    it.deletedAt == null &&
                    it.title >= from &&
                    it.title <= to
            }
        }
    }

    override suspend fun getDailyNote(dateKey: String): com.singularity.todo.feature.notes.Note? {
        val uid = currentUser.scopedUserId.value
        return store.state.value.values.firstOrNull {
            it.userId == uid && it.kind == com.singularity.todo.feature.notes.NoteKind.Daily && it.title == dateKey &&
                it.deletedAt == null
        }
    }

    override suspend fun createFromTemplate(
        templateId: com.singularity.todo.feature.notes.NoteId,
        targetTitle: String,
        targetDateKey: String?,
    ): Result<com.singularity.todo.feature.notes.NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val template = store.state.value.values.firstOrNull { it.id == templateId && it.userId == uid }
            ?: throw IllegalArgumentException("Template not found: $templateId")
        val newId = com.singularity.todo.feature.notes.NoteId(com.singularity.todo.core.ids.nextId())
        val now = Clock.System.now()
        val finalTitle = targetDateKey?.let { "$it — $targetTitle" } ?: targetTitle
        val note = template.copy(
            id = newId,
            title = finalTitle,
            bodyMarkdown = template.bodyMarkdown,
            bodyHtml = template.bodyHtml,
            kind = if (targetDateKey !=
                null
            ) {
                com.singularity.todo.feature.notes.NoteKind.Daily
            } else {
                com.singularity.todo.feature.notes.NoteKind.Plain
            },
            color = template.color,
            wordCount = template.bodyMarkdown?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0,
            charCount = template.bodyMarkdown?.length ?: 0,
            createdAt = now,
            updatedAt = now,
        )
        store.upsert(note)
        newId
    }

    override suspend fun saveAsTemplate(id: com.singularity.todo.feature.notes.NoteId): Result<Unit> = runCatching {
        store[id.value]?.let { existing ->
            store.upsert(existing.copy(kind = com.singularity.todo.feature.notes.NoteKind.Template))
        }
    }

    override suspend fun getOrCreateDailyNote(
        dateKey: String,
        fromTemplateId: com.singularity.todo.feature.notes.NoteId?,
    ): Result<com.singularity.todo.feature.notes.NoteId> = runCatching {
        val uid = currentUser.scopedUserId.value
        val existing = store.state.value.values.firstOrNull {
            it.userId == uid && it.kind == com.singularity.todo.feature.notes.NoteKind.Daily && it.title == dateKey &&
                it.deletedAt == null
        }
        if (existing != null) return@runCatching existing.id
        val now = Clock.System.now()
        val newId = com.singularity.todo.feature.notes.NoteId(com.singularity.todo.core.ids.nextId())
        val template = fromTemplateId?.let {
            store.state.value.values.firstOrNull { n -> n.id == it && n.userId == uid }
        }
        val note = com.singularity.todo.feature.notes.Note(
            id = newId,
            userId = uid,
            title = dateKey,
            bodyMarkdown = template?.bodyMarkdown,
            bodyHtml = template?.bodyHtml,
            kind = com.singularity.todo.feature.notes.NoteKind.Daily,
            color = template?.color,
            wordCount = template?.bodyMarkdown?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0,
            charCount = template?.bodyMarkdown?.length ?: 0,
            createdAt = now,
            updatedAt = now,
        )
        store.upsert(note)
        newId
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

    // ─── GenericUserScopedRepository ──────────────────────────────────────────

    override fun observeAll(): Flow<List<Profile>> = _profiles

    override fun observe(id: ProfileId): Flow<Profile?> = _profiles.map { list ->
        list.find { it.id == id }
    }

    override suspend fun get(id: ProfileId): Profile? = _profiles.value.find { it.id == id }

    override suspend fun create(item: Profile): Result<Profile> = runCatching {
        _profiles.value += item
        item
    }

    override suspend fun update(item: Profile): Result<Profile> = runCatching {
        _profiles.value = _profiles.value.map { if (it.id == item.id) item else it }
        item
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

    // ─── Profile-specific observers ────────────────────────────────────────────

    override fun activeProfile(): Flow<Profile> = _activeProfileId.map { id ->
        _profiles.value.find { it.id == id } ?: _profiles.value.first()
    }

    override val activeProfileId: StateFlow<ProfileId> = _activeProfileId

    // ─── Domain methods ───────────────────────────────────────────────────────

    override suspend fun switchTo(id: ProfileId): Result<Unit> = runCatching {
        _activeProfileId.value = id
    }

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
 * @param initialUserId Starting user ID for the fake session.
 * @param profileRepository Fake profile repository.
 * @param dispatcher CoroutineDispatcher for the internal scope. Pass
 *   `StandardTestDispatcher(testScheduler)` in tests so `advanceUntilIdle()` drives
 *   all collectors. Defaults to [Dispatchers.Default].
 */
fun FakeProfileAwareCurrentUser(
    initialUserId: UserId = UserId("test-user"),
    profileRepository: ProfileRepository = FakeProfileRepository(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
): ProfileAwareCurrentUser {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
    return fakeProfileAwareCurrentUserImpl(
        authRepository = FakeAuthRepository(Session.Anonymous(initialUserId)),
        profileRepository = profileRepository,
        scope = scope,
    )
}

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
 *
 * @param dispatcher CoroutineDispatcher for the internal scope. Pass
 *   `StandardTestDispatcher(testScheduler)` in tests so `advanceUntilIdle()` drives
 *   all collectors. Defaults to [Dispatchers.Default].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun FakeProfileAwareCurrentUser(
    authRepository: AuthRepository,
    profileRepository: ProfileRepository = FakeProfileRepository(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
): ProfileAwareCurrentUser {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
    return fakeProfileAwareCurrentUserImpl(authRepository, profileRepository, scope)
}

/**
 * Backward-compatible overload that accepts a pre-built [CoroutineScope].
 * Prefer [FakeProfileAwareCurrentUser] with a [dispatcher] parameter in new tests —
 * this overload exists only for call sites that pass `backgroundScope`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun FakeProfileAwareCurrentUser(
    authRepository: AuthRepository,
    profileRepository: ProfileRepository = FakeProfileRepository(),
    scope: kotlinx.coroutines.CoroutineScope,
): ProfileAwareCurrentUser = fakeProfileAwareCurrentUserImpl(authRepository, profileRepository, scope)

/**
 * Internal factory — shared logic for all [FakeProfileAwareCurrentUser] overloads.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun fakeProfileAwareCurrentUserImpl(
    authRepository: AuthRepository,
    profileRepository: ProfileRepository,
    scope: kotlinx.coroutines.CoroutineScope,
): ProfileAwareCurrentUser {
    val currentUser = CurrentUser(authRepository, scope)

    val initialUid = (
        authRepository.currentSession.value.let {
            when (it) {
                is Session.SignedIn -> it.userId
                is Session.Anonymous -> it.userId
                else -> UserId.anonymous
            }
        }
    )

    // Build scopedUserId from session changes via plain collect. The collector
    // is launched on the provided [scope] so when the test passes `backgroundScope`,
    // `advanceUntilIdle()` drives it via the TestDispatcher. UNDISPATCHED so the
    // collector subscribes to session synchronously (avoiding the TestDispatcher's
    // "schedule and return" semantics for the initial subscription).
    val fakeScopedUserId = MutableStateFlow(initialUid)
    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        authRepository.currentSession.collect { session ->
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

    // ─── GenericUserScopedRepository ──────────────────────────────────────────

    override suspend fun currentUserId(): String = currentUser.scopedUserId.value.value

    override suspend fun duplicateForProfile(view: SavedAgendaView, targetUserId: String): Result<SavedAgendaView> =
        runCatching {
            val now = Clock.System.now()
            val copy = view.copy(
                id = SavedAgendaViewId.generate(),
                userId = UserId(targetUserId),
                createdAt = now,
                updatedAt = now,
            )
            store.update { map -> map + (SavedAgendaViewKey.of(targetUserId, copy.id.raw) to copy) }
            copy
        }

    override fun observeAll(): Flow<List<SavedAgendaView>> = currentUser.observeForCurrentUser { uid ->
        store.map { map ->
            map.values.filter { it.userId == uid }.sortedBy { it.name }
        }
    }

    override fun observe(id: SavedAgendaViewId): Flow<SavedAgendaView?> = currentUser.observeForCurrentUser { uid ->
        store.map { map -> map[SavedAgendaViewKey.of(uid.value, id.raw)] }
    }

    override suspend fun get(id: SavedAgendaViewId): SavedAgendaView? {
        val uid = currentUser.scopedUserId.value
        return store.value[SavedAgendaViewKey.of(uid.value, id.raw)]
    }

    override suspend fun create(item: SavedAgendaView): Result<SavedAgendaView> = upsert(item)

    override suspend fun update(item: SavedAgendaView): Result<SavedAgendaView> = upsert(item)

    override suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView> = runCatching {
        store.update { map -> map + (SavedAgendaViewKey.of(view.userId.value, view.id.raw) to view) }
        view
    }

    override suspend fun delete(id: SavedAgendaViewId): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        store.update { map -> map - SavedAgendaViewKey.of(uid.value, id.raw) }
    }

    /** Synchronous upsert for tests. */
    fun upsertSync(view: SavedAgendaView) {
        store.update { map -> map + (SavedAgendaViewKey.of(view.userId.value, view.id.raw) to view) }
    }

    /** Get a view by raw ID string for tests. */
    fun getById(id: String): SavedAgendaView? = store.value.values.find { it.id.raw == id }

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

/**
 * In-memory fake for [CalendarProviderPort].
 * Holds inserted / updated events and a counter for generated event IDs.
 */
class FakeCalendarProvider : CalendarProviderPort {

    private val events = mutableMapOf<Long, CalendarSyncEvent>()
    private var nextEventId = 1000L

    /** Inject a pre-existing event (e.g. from a previous sync row). */
    fun injectEvent(eventId: Long, event: CalendarSyncEvent) {
        events[eventId] = event
        if (eventId >= nextEventId) nextEventId = eventId + 1
    }

    override suspend fun getAvailableCalendars(): Result<Map<String, String>> =
        Result.success(mapOf("cal1" to "Test Calendar"))

    override suspend fun insertEvent(event: CalendarSyncEvent): Result<Long> = Result.success(nextEventId++)

    override suspend fun updateEvent(eventId: Long, event: CalendarSyncEvent): Result<Long> {
        events[eventId] = event
        return Result.success(eventId)
    }

    override suspend fun deleteEvent(eventId: Long): Result<Unit> {
        events.remove(eventId)
        return Result.success(Unit)
    }

    override suspend fun queryEvents(calendarId: String?, fromMs: Long, toMs: Long): Result<Map<String, Long>> =
        Result.success(emptyMap())
}

/**
 * In-memory fake for [CalendarAppQueries].
 *
 * @param apps The list of apps to return from [listInstalled].
 */
class FakeCalendarAppQueries(private val apps: List<CalendarAppInfo>) : CalendarAppQueries {

    override suspend fun listInstalled(): List<CalendarAppInfo> = apps
}
