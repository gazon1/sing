package com.singularity.todo.test.fakes

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.config.RemoteConfigCacheDao
import com.singularity.todo.core.config.RemoteConfigCacheEntity
import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.AgendaViewEntity
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.LlmUsageDao
import com.singularity.todo.core.database.LlmUsageEntity
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.ProfileDao
import com.singularity.todo.core.database.ProfileEntity
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.ProjectInheritedTagGroupCrossRef
import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.ProjectReminderEntity
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.database.SavedSearchEntity
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.TagGroupEntity
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.sync.RemoteConfigDao
import com.singularity.todo.core.sync.RemoteConfigEntity
import com.singularity.todo.core.sync.SyncOutboxDao
import com.singularity.todo.core.sync.SyncOutboxEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapEntity
import com.singularity.todo.feature.timetracking.data.TimeEntryDao
import com.singularity.todo.feature.timetracking.data.TimeEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory implementation of the Room [AppDatabase] contract — same shape as
 * `androidx.room3.RoomDatabase`, same DAO interfaces, no Room runtime, no SQLite,
 * no mocks. Used to run ViewModels / UseCases / repositories against a real
 * (in-memory) data layer without spinning up a database.
 *
 * Each DAO delegates to a single `MutableStateFlow` so changes propagate reactively
 * to subscribers — exactly like Room's invalidation tracker.
 */
class FakeAppDatabase : AppDatabase() {

    private val _tasks = MutableStateFlow<Map<String, TaskEntity>>(emptyMap())
    private val _taskTags = MutableStateFlow<List<TaskTagCrossRef>>(emptyList())
    private val _taskDependencies = MutableStateFlow<List<TaskDependencyCrossRef>>(emptyList())
    private val _notes = MutableStateFlow<Map<String, NoteEntity>>(emptyMap())
    private val _projects = MutableStateFlow<Map<String, ProjectEntity>>(emptyMap())
    private val _tags = MutableStateFlow<Map<String, TagEntity>>(emptyMap())
    private val _outbox = MutableStateFlow<Map<String, SyncOutboxEntity>>(emptyMap())
    private val _attachments = MutableStateFlow<Map<String, AttachmentEntity>>(emptyMap())
    private val _reminders =
        MutableStateFlow<Map<Pair<String, String>, com.singularity.todo.core.database.TaskReminderEntity>>(
            emptyMap(),
        )
    private val _projectReminders = MutableStateFlow<Map<Pair<String, String>, ProjectReminderEntity>>(emptyMap())
    private val _checklist = MutableStateFlow<Map<String, ChecklistItemEntity>>(emptyMap())
    private val _llmUsage = MutableStateFlow<Map<String, LlmUsageEntity>>(emptyMap())
    private val _profiles = MutableStateFlow<Map<String, ProfileEntity>>(emptyMap())
    private val _agendaViews = MutableStateFlow<Map<String, AgendaViewEntity>>(emptyMap())
    private val _remoteConfigs = MutableStateFlow<Map<String, RemoteConfigEntity>>(emptyMap())
    private val _remoteConfigCache = MutableStateFlow<RemoteConfigCacheEntity?>(null)
    private val _calendarSyncTaskMap = MutableStateFlow<Map<String, CalendarSyncTaskMapEntity>>(emptyMap())
    private val _savedSearches = MutableStateFlow<Map<String, SavedSearchEntity>>(emptyMap())
    private val _tagGroups = MutableStateFlow<Map<String, TagGroupEntity>>(emptyMap())
    private val _projectTagGroups = MutableStateFlow<List<ProjectInheritedTagGroupCrossRef>>(emptyList())
    private val _timeEntries = MutableStateFlow<Map<String, TimeEntryEntity>>(emptyMap())

    override fun taskDao(): TaskDao = FakeTaskDao(_tasks, _taskTags, _taskDependencies)
    override fun noteDao(): NoteDao = FakeNoteDao(_notes)
    override fun projectDao(): ProjectDao = FakeProjectDao(_projects)
    override fun tagDao(): TagDao = FakeTagDao(_tags)
    override fun syncOutboxDao(): SyncOutboxDao = FakeSyncOutboxDao(_outbox)
    override fun attachmentDao(): AttachmentDao = FakeAttachmentDao(_attachments)
    override fun reminderDao(): ReminderDao = FakeReminderDao(_reminders)
    override fun projectReminderDao(): ProjectReminderDao = FakeProjectReminderDao(_projectReminders)
    override fun checklistDao(): ChecklistDao = FakeChecklistDao(_checklist)
    override fun llmUsageDao(): LlmUsageDao = FakeLlmUsageDao(_llmUsage)
    override fun profileDao(): ProfileDao = FakeProfileDao(_profiles)
    override fun agendaViewDao(): AgendaViewDao = FakeAgendaViewDao(_agendaViews)
    override fun remoteConfigDao(): RemoteConfigDao = FakeRemoteConfigDao(_remoteConfigs)
    override fun remoteConfigCacheDao(): RemoteConfigCacheDao = FakeRemoteConfigCacheDao(_remoteConfigCache)
    override fun calendarSyncTaskMapDao(): CalendarSyncTaskMapDao = FakeCalendarSyncTaskMapDao(_calendarSyncTaskMap)
    override fun savedSearchDao(): SavedSearchDao = FakeSavedSearchDao(_savedSearches)
    override fun tagGroupDao(): TagGroupDao = FakeTagGroupDao(_tagGroups)
    override fun projectInheritedTagGroupDao(): ProjectInheritedTagGroupDao = FakeProjectInheritedTagGroupDao(
        _projectTagGroups,
    )
    override fun timeEntryDao(): TimeEntryDao = FakeTimeEntryDao(_timeEntries)

    override suspend fun clearAllTables() {
        _tasks.value = emptyMap()
        _taskTags.value = emptyList()
        _taskDependencies.value = emptyList()
        _notes.value = emptyMap()
        _projects.value = emptyMap()
        _tags.value = emptyMap()
        _outbox.value = emptyMap()
        _attachments.value = emptyMap()
        _reminders.value = emptyMap()
        _projectReminders.value = emptyMap()
        _checklist.value = emptyMap()
        _llmUsage.value = emptyMap()
        _profiles.value = emptyMap()
        _agendaViews.value = emptyMap()
        _remoteConfigs.value = emptyMap()
        _remoteConfigCache.value = null
        _calendarSyncTaskMap.value = emptyMap()
        _savedSearches.value = emptyMap()
        _tagGroups.value = emptyMap()
        _projectTagGroups.value = emptyList()
        _timeEntries.value = emptyMap()
    }

    // ─── Seed helpers ────────────────────────────────────────────────────────
    // Each is a single mutation, no fixture DSL — easier to read in test setup.

    fun seedTasks(items: List<TaskEntity>) {
        _tasks.value = items.associateBy { it.id }
    }
    fun seedNotes(items: List<NoteEntity>) {
        _notes.value = items.associateBy { it.id }
    }
    fun seedProjects(items: List<ProjectEntity>) {
        _projects.value = items.associateBy { it.id }
    }
    fun seedTags(items: List<TagEntity>) {
        _tags.value = items.associateBy { it.id }
    }
    fun seedOutbox(items: List<SyncOutboxEntity>) {
        _outbox.value = items.associateBy { it.patchId }
    }
    fun seedAttachments(items: List<AttachmentEntity>) {
        _attachments.value = items.associateBy { it.id }
    }
    fun seedReminders(items: List<com.singularity.todo.core.database.TaskReminderEntity>) {
        _reminders.value = items.associateBy { it.userId to it.id }
    }
    fun seedChecklist(items: List<ChecklistItemEntity>) {
        _checklist.value = items.associateBy { it.id }
    }
    fun seedLlUsage(items: List<LlmUsageEntity>) {
        _llmUsage.value = items.associateBy { it.id }
    }
    fun seedProfiles(items: List<ProfileEntity>) {
        _profiles.value = items.associateBy { it.id }
    }
    fun seedAgendaViews(items: List<AgendaViewEntity>) {
        _agendaViews.value = items.associateBy { it.id }
    }
    fun seedSavedSearches(items: List<SavedSearchEntity>) {
        _savedSearches.value = items.associateBy { it.id }
    }
    fun seedTagGroups(items: List<TagGroupEntity>) {
        _tagGroups.value = items.associateBy { it.id }
    }
    fun seedProjectTagGroups(items: List<ProjectInheritedTagGroupCrossRef>) {
        _projectTagGroups.value = items
    }

    /**
     * Returns a human-readable snapshot of all in-memory tables, for inclusion in
     * [FailureBundle] reports when a test fails.
     *
     * Each table is prefixed with `--- <TableName> ---` and a row count, then the
     * rows in insertion order.
     */
    fun dumpAll(): String = buildString {
        appendLine("=== FakeAppDatabase snapshot ===")
        appendLine()
        appendLine("--- Tasks (${_tasks.value.size} rows) ---")
        _tasks.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- TaskTags (${_taskTags.value.size} rows) ---")
        _taskTags.value.forEach { appendLine(it) }
        appendLine()
        appendLine("--- TaskDependencies (${_taskDependencies.value.size} rows) ---")
        _taskDependencies.value.forEach { appendLine(it) }
        appendLine()
        appendLine("--- Notes (${_notes.value.size} rows) ---")
        _notes.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- Projects (${_projects.value.size} rows) ---")
        _projects.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- Tags (${_tags.value.size} rows) ---")
        _tags.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- SyncOutbox (${_outbox.value.size} rows) ---")
        _outbox.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- Attachments (${_attachments.value.size} rows) ---")
        _attachments.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- Reminders (${_reminders.value.size} rows) ---")
        _reminders.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- ProjectReminders (${_projectReminders.value.size} rows) ---")
        _projectReminders.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- Checklist (${_checklist.value.size} rows) ---")
        _checklist.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- LlmUsage (${_llmUsage.value.size} rows) ---")
        _llmUsage.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- Profiles (${_profiles.value.size} rows) ---")
        _profiles.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- AgendaViews (${_agendaViews.value.size} rows) ---")
        _agendaViews.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- RemoteConfigs (${_remoteConfigs.value.size} rows) ---")
        _remoteConfigs.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- SavedSearches (${_savedSearches.value.size} rows) ---")
        _savedSearches.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- TagGroups (${_tagGroups.value.size} rows) ---")
        _tagGroups.value.values.forEach { appendLine(it) }
        appendLine()
        appendLine("--- ProjectTagGroups (${_projectTagGroups.value.size} rows) ---")
        _projectTagGroups.value.forEach { appendLine(it) }
    }
}

// ─── TaskDao ─────────────────────────────────────────────────────────────────

private class FakeTaskDao(
    private val store: MutableStateFlow<Map<String, TaskEntity>>,
    private val crossRefs: MutableStateFlow<List<TaskTagCrossRef>>,
    private val depRefs: MutableStateFlow<List<TaskDependencyCrossRef>>,
) : TaskDao {

    override fun watchActive(userId: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t -> t.userId == userId && t.archivedAt == null }
            .sortedWith(compareBy({ it.dueDate ?: "\uFFFF" }, { !it.isPinned }))
    }

    override fun watchById(id: String): Flow<TaskEntity?> = store.map { it[id] }

    override fun watchByIdForUser(id: String, userId: String): Flow<TaskEntity?> =
        store.map { it[id]?.takeIf { t -> t.userId == userId } }

    override fun watchTrash(userId: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t -> t.userId == userId && t.archivedAt != null }
            .sortedByDescending { it.archivedAt }
    }

    override suspend fun getTrashForUser(userId: String): List<TaskEntity> = store.value.values
        .filter { t -> t.userId == userId && t.archivedAt != null }
        .sortedByDescending { it.archivedAt }

    override fun watchSomeday(userId: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t -> t.userId == userId && t.someday && t.archivedAt == null }
            .sortedByDescending { it.createdAt }
    }

    override fun watchByDate(userId: String, date: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t -> t.userId == userId && t.archivedAt == null && t.dueDate == date }
            .sortedWith(compareBy({ !it.isPinned }, { it.dueTime ?: "" }))
    }

    override fun watchUpcoming(userId: String, today: String, endDate: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t ->
            val due = t.dueDate ?: return@filter false
            t.userId == userId && t.archivedAt == null && due > today && due <= endDate
        }.sortedBy { it.dueDate }
    }

    override fun watchByProject(userId: String, projectId: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t -> t.userId == userId && t.projectId == projectId && t.archivedAt == null }
            .sortedWith(compareBy({ !it.isPinned }, { it.dueDate ?: "" }))
    }

    override fun watchByDateRange(userId: String, from: String, to: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t ->
            val due = t.dueDate ?: return@filter false
            t.userId == userId && t.archivedAt == null && due >= from && due <= to
        }.sortedBy { it.dueDate }
    }

    override fun watchByTag(userId: String, tagId: String): Flow<List<TaskEntity>> =
        kotlinx.coroutines.flow.combine(store, crossRefs) { tasks, refs ->
            val taskIds = refs.filter { it.tagId == tagId }.map { it.taskId }.toSet()
            tasks.values.filter { t ->
                t.userId == userId && t.archivedAt == null && t.id in taskIds
            }.sortedWith(compareBy({ it.dueDate ?: "\uFFFF" }, { !it.isPinned }))
        }

    override fun watchByAnyTag(userId: String, tagIds: List<String>): Flow<List<TaskEntity>> =
        kotlinx.coroutines.flow.combine(store, crossRefs) { tasks, refs ->
            val taskIds = refs.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
            tasks.values.filter { t ->
                t.userId == userId && t.archivedAt == null && t.id in taskIds
            }.sortedWith(compareBy({ it.dueDate ?: "\uFFFF" }, { !it.isPinned }))
        }

    override fun watchByAllTags(userId: String, tagIds: List<String>, size: Int): Flow<List<TaskEntity>> =
        kotlinx.coroutines.flow.combine(store, crossRefs) { tasks, refs ->
            val matchingTaskIds = refs.filter { it.tagId in tagIds }
                .groupBy { it.taskId }
                .filterValues { group -> group.map { it.tagId }.toSet() == tagIds.toSet() }
                .keys
            tasks.values.filter { t ->
                t.userId == userId && t.archivedAt == null && t.id in matchingTaskIds
            }.sortedWith(compareBy({ it.dueDate ?: "\uFFFF" }, { !it.isPinned }))
        }

    override fun watchByPriorities(userId: String, priorities: List<String>): Flow<List<TaskEntity>> =
        store.map { tasks ->
            tasks.values.filter { t ->
                t.userId == userId && t.archivedAt == null && t.priority.name in priorities
            }.sortedWith(compareBy({ it.dueDate ?: "\uFFFF" }, { !it.isPinned }))
        }

    override fun watchByRegexp(userId: String, pattern: String): Flow<List<TaskEntity>> = store.map { tasks ->
        tasks.values.filter { t ->
            t.userId == userId && t.archivedAt == null &&
                t.title.contains(pattern, ignoreCase = true)
        }.sortedWith(compareBy({ it.dueDate ?: "\uFFFF" }, { !it.isPinned }))
    }

    override fun watchPinned(userId: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t -> t.userId == userId && t.isPinned && t.archivedAt == null }
            .sortedWith(compareBy({ !it.isPinned }, { it.dueDate ?: "" }))
    }

    override fun watchSearchResults(userId: String, q: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t ->
            t.userId == userId && t.archivedAt == null &&
                (
                    t.title.contains(q, ignoreCase = true) ||
                        (t.description?.contains(q, ignoreCase = true) == true)
                )
        }
    }

    override suspend fun upsert(task: TaskEntity) {
        store.update { it + (task.id to task) }
    }

    /**
     * Applies [fn] to the task only when it exists **and** belongs to [userId],
     * returning the affected row count — mirroring the `WHERE id AND user_id`
     * contract of the real DAO. Returning 0 here is what makes tests able to
     * assert cross-user isolation instead of passing vacuously.
     */
    private fun mutateTaskForUser(id: String, userId: String, fn: (TaskEntity) -> TaskEntity): Int {
        var affected = 0
        store.update { current ->
            val existing = current[id]
            if (existing == null || existing.userId != userId) {
                current
            } else {
                affected = 1
                current + (id to fn(existing))
            }
        }
        return affected
    }

    private fun ownsTask(id: String, userId: String): Boolean = store.value[id]?.userId == userId

    override suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int =
        mutateTaskForUser(id, userId) { it.copy(archivedAt = ts, updatedAt = ts) }

    override suspend fun restoreForUser(id: String, ts: Long, userId: String): Int =
        mutateTaskForUser(id, userId) { it.copy(archivedAt = null, updatedAt = ts) }

    override suspend fun getById(id: String): TaskEntity? = store.value[id]

    override suspend fun getByIdForUser(id: String, userId: String): TaskEntity? =
        store.value[id]?.takeIf { it.userId == userId }

    override suspend fun markCompleteForUser(id: String, ts: Long, userId: String): Int =
        mutateTaskForUser(id, userId) { it.copy(completedAt = ts, updatedAt = ts) }

    override suspend fun markIncompleteForUser(id: String, ts: Long, userId: String): Int =
        mutateTaskForUser(id, userId) { it.copy(completedAt = null, updatedAt = ts) }

    override suspend fun setPinnedForUser(id: String, pinned: Boolean, ts: Long, userId: String): Int =
        mutateTaskForUser(id, userId) { it.copy(isPinned = pinned, updatedAt = ts) }

    override suspend fun archiveCompletedForUser(ts: Long, userId: String): Int {
        var count = 0
        store.update { current ->
            current.mapValues { (_, task) ->
                if (task.userId == userId && task.completedAt != null && task.archivedAt == null) {
                    count++
                    task.copy(archivedAt = ts, updatedAt = ts)
                } else {
                    task
                }
            }
        }
        return count
    }

    override suspend fun upsertTagCrossRef(ref: TaskTagCrossRef) {
        crossRefs.update { it + ref }
    }

    override suspend fun upsertTagCrossRefForUser(taskId: String, tagId: String, userId: String) {
        if (!ownsTask(taskId, userId)) return
        crossRefs.update { it + TaskTagCrossRef(taskId = taskId, tagId = tagId) }
    }

    override suspend fun removeTagRefForUser(taskId: String, tagId: String, userId: String): Int {
        if (!ownsTask(taskId, userId)) return 0
        crossRefs.update { it.filterNot { r -> r.taskId == taskId && r.tagId == tagId } }
        return 1
    }

    override fun getTagIdsForTask(taskId: String): Flow<List<String>> =
        crossRefs.map { refs -> refs.filter { it.taskId == taskId }.map { it.tagId } }

    override fun getTagIdsForUser(taskId: String, userId: String): Flow<List<String>> =
        combine(crossRefs, store) { refs, tasks ->
            if (tasks[taskId]?.userId != userId) {
                emptyList()
            } else {
                refs.filter { it.taskId == taskId }.map { it.tagId }
            }
        }

    override fun getDependencyIdsForTask(taskId: String): Flow<List<String>> =
        depRefs.map { refs -> refs.filter { it.taskId == taskId }.map { it.dependsOnTaskId } }

    override fun getDependencyIdsForUser(taskId: String, userId: String): Flow<List<String>> =
        combine(depRefs, store) { refs, tasks ->
            if (tasks[taskId]?.userId != userId) {
                emptyList()
            } else {
                refs.filter { it.taskId == taskId }.map { it.dependsOnTaskId }
            }
        }

    override fun getBlockingTaskIdsForTask(taskId: String): Flow<List<String>> =
        depRefs.map { refs -> refs.filter { it.dependsOnTaskId == taskId }.map { it.taskId } }

    override fun getBlockingTaskIdsForUser(taskId: String, userId: String): Flow<List<String>> =
        combine(depRefs, store) { refs, tasks ->
            if (tasks[taskId]?.userId != userId) {
                emptyList()
            } else {
                refs.filter { it.dependsOnTaskId == taskId }.map { it.taskId }
            }
        }

    override suspend fun upsertDependency(ref: TaskDependencyCrossRef) {
        depRefs.update { existing ->
            if (existing.any { it.taskId == ref.taskId && it.dependsOnTaskId == ref.dependsOnTaskId }) {
                existing
            } else {
                existing + ref
            }
        }
    }

    override suspend fun upsertDependencyForUser(taskId: String, depId: String, verb: String, userId: String) {
        if (!ownsTask(taskId, userId)) return
        upsertDependency(TaskDependencyCrossRef(taskId = taskId, dependsOnTaskId = depId, verb = verb))
    }

    override suspend fun removeDependencyForUser(taskId: String, depId: String, userId: String): Int {
        if (!ownsTask(taskId, userId)) return 0
        depRefs.update { it.filterNot { r -> r.taskId == taskId && r.dependsOnTaskId == depId } }
        return 1
    }

    override suspend fun removeDependencyForVerb(taskId: String, depId: String, verb: String, userId: String): Int {
        if (!ownsTask(taskId, userId)) return 0
        val before = depRefs.value.count { r -> r.taskId == taskId && r.dependsOnTaskId == depId && r.verb == verb }
        depRefs.update { it.filterNot { r -> r.taskId == taskId && r.dependsOnTaskId == depId && r.verb == verb } }
        return before
    }

    override fun observeTypedDependenciesForUser(taskId: String, userId: String): Flow<List<TaskDependencyCrossRef>> =
        depRefs.map { refs -> refs.filter { it.taskId == taskId } }

    override suspend fun clearDependenciesForUser(taskId: String, userId: String): Int {
        if (!ownsTask(taskId, userId)) return 0
        val before = depRefs.value.count { it.taskId == taskId }
        depRefs.update { it.filterNot { r -> r.taskId == taskId } }
        return before
    }

    override suspend fun listAllForUser(userId: String): List<TaskEntity> =
        store.value.values.filter { it.userId == userId }

    override suspend fun listAllDependenciesForUser(userId: String): List<TaskDependencyCrossRef> =
        depRefs.value.filter { ref ->
            store.value[ref.taskId]?.userId == userId
        }

    override suspend fun listAllTagsForUser(userId: String): List<TaskTagCrossRef> = crossRefs.value.filter { ref ->
        store.value[ref.taskId]?.userId == userId
    }

    override fun observeTagCrossRefs(userId: String): Flow<List<TaskTagCrossRef>> = crossRefs

    override fun observeDependencyCrossRefs(userId: String): Flow<List<TaskDependencyCrossRef>> = depRefs

    override suspend fun searchTitles(userId: String, q: String): List<TaskEntity> = store.value.values.filter {
        it.userId == userId && it.archivedAt == null && it.title.contains(q, ignoreCase = true)
    }.sortedByDescending { it.updatedAt }.take(20)

    override suspend fun setOutgoingLinksForUser(id: String, linksJson: String, updatedAt: Long, userId: String): Int =
        mutateTaskForUser(id, userId) { it.copy(outgoingLinks = linksJson, updatedAt = updatedAt) }

    override suspend fun getBacklinkTasks(taskId: String, userId: String): List<TaskEntity> =
        store.value.values.filter { t ->
            t.userId == userId && t.archivedAt == null && t.outgoingLinks.contains("task://$taskId")
        }.take(20)
}

// ─── NoteDao ──────────────────────────────────────────────────────────────────

private class FakeNoteDao(private val store: MutableStateFlow<Map<String, NoteEntity>>) : NoteDao {

    override fun watchAll(userId: String): Flow<List<NoteEntity>> = store.map {
        it.values.filter { n -> n.userId == userId && n.archivedAt == null && n.deletedAt == null }
            .sortedWith(compareBy({ !it.isPinned }, { it.sortOrder }, { -it.updatedAt }))
    }

    override fun watchPinned(userId: String): Flow<List<NoteEntity>> = store.map {
        it.values.filter { n -> n.userId == userId && n.isPinned && n.archivedAt == null && n.deletedAt == null }
            .sortedByDescending { it.pinnedAt }
    }

    override fun watchArchived(userId: String): Flow<List<NoteEntity>> = store.map {
        it.values.filter { n -> n.userId == userId && n.archivedAt != null && n.deletedAt == null }
            .sortedByDescending { it.archivedAt }
    }

    override fun watchRootNotes(userId: String): Flow<List<NoteEntity>> = store.map {
        it.values.filter { n ->
            n.userId == userId && n.parentNoteId == null && !n.isFolder &&
                n.archivedAt == null && n.deletedAt == null
        }
            .sortedWith(compareBy({ it.sortOrder }, { -it.updatedAt }))
    }

    override fun watchByIdForUser(id: String, userId: String): Flow<NoteEntity?> = store.map { n ->
        n.values.find { it.id == id && it.userId == userId }
    }
    override suspend fun getByIdForUser(id: String, userId: String): NoteEntity? =
        store.value.values.find { it.id == id && it.userId == userId }

    override fun watchChildrenForUser(parentId: String, userId: String): Flow<List<NoteEntity>> = store.map { items ->
        items.values
            .filter { n ->
                n.parentNoteId == parentId &&
                    n.userId == userId &&
                    n.archivedAt == null &&
                    n.deletedAt == null
            }
            .sortedWith(compareBy({ it.sortOrder }, { it.title }))
    }

    override fun watchSearchByTitle(userId: String, q: String): Flow<List<NoteEntity>> = store.map { map ->
        map.values.filter { n ->
            n.userId == userId && n.archivedAt == null && n.deletedAt == null &&
                n.title.contains(q, ignoreCase = true)
        }.sortedByDescending { it.updatedAt }.take(20)
    }

    override fun watchByTaskForUser(taskId: String, userId: String): Flow<List<NoteEntity>> = store.map { map ->
        map.values.filter { n ->
            n.taskId == taskId && n.userId == userId && n.deletedAt == null
        }.sortedByDescending { n -> n.createdAt }.take(50)
    }

    override suspend fun upsert(note: NoteEntity) {
        store.update { it + (note.id to note) }
    }

    /**
     * Applies [fn] only when the note exists **and** belongs to [userId],
     * returning the affected row count — mirroring the real DAO's
     * `WHERE id AND user_id` contract.
     */
    private fun mutateForUser(id: String, userId: String, fn: (NoteEntity) -> NoteEntity): Int {
        var affected = 0
        store.update { current ->
            val existing = current[id]
            if (existing == null || existing.userId != userId) {
                current
            } else {
                affected = 1
                current + (id to fn(existing))
            }
        }
        return affected
    }

    override suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(deletedAt = ts, updatedAt = ts) }

    override suspend fun restoreForUser(id: String, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(deletedAt = null, updatedAt = ts) }

    override suspend fun archiveForUser(id: String, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(archivedAt = ts, updatedAt = ts) }

    override suspend fun unarchiveForUser(id: String, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(archivedAt = null, updatedAt = ts) }

    override suspend fun updateContentForUser(
        id: String,
        title: String,
        markdown: String,
        html: String,
        wordCount: Int,
        charCount: Int,
        updatedAt: Long,
        userId: String,
    ): Int = mutateForUser(id, userId) {
        it.copy(
            title = title,
            bodyMarkdown = markdown,
            bodyHtml = html,
            wordCount = wordCount,
            charCount = charCount,
            updatedAt = updatedAt,
        )
    }

    override suspend fun setPinnedForUser(id: String, pinned: Boolean, pinnedAt: Long?, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(isPinned = pinned, pinnedAt = pinnedAt, updatedAt = ts) }

    override suspend fun setColorForUser(id: String, color: Int?, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(color = color, updatedAt = ts) }

    override suspend fun setSortOrderForUser(id: String, sortOrder: Int, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(sortOrder = sortOrder, updatedAt = ts) }

    override suspend fun listAllForUser(userId: String): List<NoteEntity> =
        store.value.values.filter { it.userId == userId }

    override suspend fun searchByTitle(userId: String, q: String): List<NoteEntity> = store.value.values.filter {
        it.userId == userId && it.deletedAt == null && it.title.contains(
            q,
            ignoreCase = true,
        )
    }
        .sortedByDescending { it.updatedAt }.take(20)

    override suspend fun setOutgoingLinksForUser(id: String, linksJson: String, updatedAt: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(outgoingLinks = linksJson, updatedAt = updatedAt) }

    override suspend fun getBacklinkNotes(noteId: String, userId: String): List<NoteEntity> =
        store.value.values.filter { n ->
            n.userId == userId && n.deletedAt == null && n.outgoingLinks.contains("note://$noteId")
        }.take(20)

    override suspend fun getNotesLinkingToTask(taskId: String, userId: String): List<NoteEntity> =
        store.value.values.filter { n ->
            n.userId == userId && n.deletedAt == null && n.outgoingLinks.contains("task://$taskId")
        }.take(20)

    // ── Templates and daily notes ─────────────────────────────────────────────

    override fun watchTemplates(userId: String): Flow<List<NoteEntity>> = store.map { map ->
        map.values.filter { n ->
            n.userId == userId && n.kind.name == "Template" && n.deletedAt == null
        }.sortedBy { it.title }
    }

    override suspend fun getDailyNote(userId: String, dateKey: String): NoteEntity? = store.value.values.find {
        it.userId == userId && it.kind.name == "Daily" && it.title == dateKey && it.deletedAt == null
    }

    override fun watchDailyNotesInRange(userId: String, from: String, to: String): Flow<List<NoteEntity>> =
        store.map { map ->
            map.values.filter { n ->
                n.userId == userId && n.kind.name == "Daily" && n.deletedAt == null && n.title >= from && n.title <= to
            }.sortedBy { it.title }
        }

    override suspend fun setKindForUser(id: String, kind: String, ts: Long, userId: String): Int =
        mutateForUser(id, userId) {
            it.copy(kind = com.singularity.todo.feature.notes.NoteKind.valueOf(kind), updatedAt = ts)
        }
}

// ─── ProjectDao ────────────────────────────────────────────────────────────────

private class FakeProjectDao(private val store: MutableStateFlow<Map<String, ProjectEntity>>) : ProjectDao {

    override fun watchAll(userId: String): Flow<List<ProjectEntity>> = store.map {
        it.values.filter { p -> p.userId == userId && !p.isDeleted }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
    }

    // ─── UserId-scoped reads (Phase 2.8 fix) ──────────────────────────────────
    override fun watchByIdForUser(id: String, userId: String): Flow<ProjectEntity?> = store.map {
        it[id]?.takeIf { p ->
            p.userId ==
                userId
        }
    }
    override suspend fun getByIdForUser(id: String, userId: String): ProjectEntity? = store.value[id]?.takeIf {
        it.userId ==
            userId
    }
    override fun watchByParentForUser(parentId: String, userId: String): Flow<List<ProjectEntity>> =
        store.map { it.values.filter { it.parentId == parentId && it.userId == userId && !it.isDeleted } }

    override fun watchAllWithCounts(
        userId: String,
    ): Flow<List<com.singularity.todo.core.database.ProjectWithCountRow>> = store.map { map ->
        map.values.filter { it.userId == userId && !it.isDeleted }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .map { entity ->
                com.singularity.todo.core.database.ProjectWithCountRow(
                    project = entity,
                    totalCount = 0,
                    completedCount = 0,
                )
            }
    }
    override suspend fun setParentForUser(id: String, parentId: String?, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(parentId = parentId, updatedAt = ts) }
    override suspend fun setSortOrderForUser(id: String, sortOrder: Int, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(sortOrder = sortOrder, updatedAt = ts) }
    override suspend fun restoreForUser(id: String, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(isDeleted = false, deletedAt = null, updatedAt = ts) }
    override suspend fun findByIdempotencyKeyForUser(key: String, userId: String): ProjectEntity? =
        store.value.values.firstOrNull { it.idempotencyKey == key && it.userId == userId }
    override suspend fun findByNameForUser(userId: String, name: String): ProjectEntity? =
        store.value.values.firstOrNull {
            it.userId == userId && !it.isDeleted && it.name.equals(
                name,
                ignoreCase = true,
            )
        }
    override suspend fun upsert(project: ProjectEntity) {
        store.update { it + (project.id to project) }
    }
    override suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(isDeleted = true, deletedAt = ts, updatedAt = ts) }
    override suspend fun listAllForUser(userId: String): List<ProjectEntity> =
        store.value.values.filter { it.userId == userId }

    private fun mutateForUser(id: String, userId: String, fn: (ProjectEntity) -> ProjectEntity): Int {
        store.update { current ->
            val existing = current[id] ?: return@update current
            if (existing.userId != userId) return@update current
            current + (id to fn(existing))
        }
        return 1
    }

    private fun mutate(id: String, fn: (ProjectEntity) -> ProjectEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── TagDao ────────────────────────────────────────────────────────────────────

private class FakeTagDao(private val store: MutableStateFlow<Map<String, TagEntity>>) : TagDao {

    override fun watchAll(userId: String): Flow<List<TagEntity>> = store.map {
        it.values.filter { t -> t.userId == userId && t.deletedAt == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
    }

    override fun watchById(id: String): Flow<TagEntity?> = store.map { it[id] }

    override fun watchByIdForUser(id: String, userId: String): Flow<TagEntity?> = store.map {
        it[id]?.takeIf { t -> t.userId == userId && t.deletedAt == null }
    }

    override suspend fun getByIdForUser(id: String, userId: String): TagEntity? =
        store.value[id]?.takeIf { it.userId == userId && it.deletedAt == null }

    override suspend fun findByNameForUser(userId: String, name: String): TagEntity? = store.value.values.firstOrNull {
        it.userId == userId && it.deletedAt == null && it.name.equals(
            name,
            ignoreCase = true,
        )
    }

    override suspend fun upsert(tag: TagEntity) {
        store.update { it + (tag.id to tag) }
    }

    override suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int {
        val entity = store.value[id]
        return if (entity != null && entity.userId == userId) {
            mutate(id) { it.copy(deletedAt = ts, updatedAt = ts) }
            1
        } else {
            0
        }
    }

    override suspend fun listAllForUser(userId: String): List<TagEntity> =
        store.value.values.filter { it.userId == userId }

    override suspend fun listByGroupForUser(groupId: String, userId: String): List<TagEntity> =
        store.value.values.filter { it.groupId == groupId && it.userId == userId && it.deletedAt == null }

    override suspend fun clearGroupForUser(groupId: String, ts: Long, userId: String): Int {
        val members = listByGroupForUser(groupId, userId)
        members.forEach { mutate(it.id) { tag -> tag.copy(groupId = null, updatedAt = ts) } }
        return members.size
    }

    private fun mutate(id: String, fn: (TagEntity) -> TagEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── SyncOutboxDao ────────────────────────────────────────────────────────────

private class FakeSyncOutboxDao(private val store: MutableStateFlow<Map<String, SyncOutboxEntity>>) : SyncOutboxDao {

    override fun watchPending(): Flow<List<SyncOutboxEntity>> = store.map { it.values.sortedBy { it.createdAt } }

    override suspend fun getPending(): List<SyncOutboxEntity> = store.value.values.sortedBy { it.createdAt }

    override suspend fun insert(entity: SyncOutboxEntity) {
        store.update { it + (entity.patchId to entity) }
    }
    override suspend fun delete(id: String) {
        store.update { it - id }
    }
    override suspend fun markFailed(id: String, error: String) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to existing.copy(attempts = existing.attempts + 1, lastError = error))
        }
    }
    override suspend fun deleteByEntity(entityId: String) {
        store.update { it.filterValues { e -> e.entityId != entityId } }
    }
    override suspend fun clearAll() {
        store.value = emptyMap()
    }
}

// ─── AttachmentDao ────────────────────────────────────────────────────────────

private class FakeAttachmentDao(private val store: MutableStateFlow<Map<String, AttachmentEntity>>) : AttachmentDao {

    override fun watchByTask(taskId: String): Flow<List<AttachmentEntity>> = store.map {
        it.values.filter { a -> a.taskId == taskId && a.deletedAt == null }
            .sortedByDescending { it.createdAt }
    }

    // ─── UserId-scoped reads (Phase 2.8 fix) ──────────────────────────────────
    override fun watchByTaskForUser(taskId: String, userId: String): Flow<List<AttachmentEntity>> = store.map {
        it.values.filter { a -> a.taskId == taskId && a.userId == userId && a.deletedAt == null }
            .sortedByDescending { it.createdAt }
    }
    override fun watchAll(userId: String): Flow<List<AttachmentEntity>> = store.map {
        it.values.filter { a -> a.userId == userId && a.deletedAt == null }
            .sortedByDescending { it.createdAt }
    }
    override suspend fun getById(id: String, userId: String): AttachmentEntity? =
        store.value[id]?.takeIf { a -> a.userId == userId && a.deletedAt == null }
    override fun watchByIdForUser(id: String, userId: String): Flow<AttachmentEntity?> = store.map {
        it[id]?.takeIf { a ->
            a.userId ==
                userId
        }
    }
    override fun watchBySyncStatusForUser(status: String, userId: String): Flow<List<AttachmentEntity>> =
        store.map { it.values.filter { a -> a.syncStatus == status && a.userId == userId && a.deletedAt == null } }

    // ─── Legacy ────────────────────────────────────────────────────────────────
    override fun watchById(id: String): Flow<AttachmentEntity?> = store.map { it[id] }

    override suspend fun upsert(entity: AttachmentEntity) {
        store.update { it + (entity.id to entity) }
    }
    override suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int {
        val entity = store.value[id]
        if (entity == null || entity.userId != userId) return 0
        mutate(id) { it.copy(deletedAt = ts, updatedAt = ts) }
        return 1
    }

    override suspend fun deleteForUser(id: String, userId: String): Int {
        val entity = store.value[id]
        if (entity == null || entity.userId != userId) return 0
        store.update { it - id }
        return 1
    }

    override fun watchBySyncStatus(status: String): Flow<List<AttachmentEntity>> =
        store.map { it.values.filter { a -> a.syncStatus == status && a.deletedAt == null } }

    override suspend fun listAllForUser(userId: String): List<AttachmentEntity> =
        store.value.values.filter { it.userId == userId }

    private fun mutate(id: String, fn: (AttachmentEntity) -> AttachmentEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── ReminderDao ──────────────────────────────────────────────────────────────

private class FakeProjectReminderDao(
    private val store: MutableStateFlow<Map<Pair<String, String>, ProjectReminderEntity>>,
) : ProjectReminderDao {
    override fun watchAll(userId: String): Flow<List<ProjectReminderEntity>> =
        store.map { it.values.filter { r -> r.userId == userId }.sortedBy { it.fireAt } }

    override fun watchByProject(projectId: String, userId: String): Flow<List<ProjectReminderEntity>> = store.map {
        it.values.filter { r -> r.projectId == projectId && r.userId == userId }.sortedBy { r -> r.fireAt }
    }

    override fun getDueBefore(now: Long, userId: String): Flow<List<ProjectReminderEntity>> =
        store.map { it.values.filter { r -> r.fireAt <= now && r.userId == userId }.sortedBy { r -> r.fireAt } }

    override fun getRecentDueBefore(now: Long, userId: String, limit: Int): Flow<List<ProjectReminderEntity>> =
        store.map {
            it.values.filter { r -> r.fireAt <= now && r.userId == userId }
                .sortedByDescending { r -> r.fireAt }
                .take(limit)
        }

    override suspend fun upsert(reminder: ProjectReminderEntity) {
        store.update { it + ((reminder.userId to reminder.id) to reminder) }
    }

    override suspend fun delete(id: String, userId: String) {
        store.update { it - (userId to id) }
    }

    override suspend fun deleteByProject(projectId: String, userId: String) {
        store.update { m -> m.filterValues { !(it.projectId == projectId && it.userId == userId) } }
    }

    override suspend fun getById(id: String, userId: String): ProjectReminderEntity? = store.value[(userId to id)]

    override fun watchByIdForUser(id: String, userId: String): Flow<ProjectReminderEntity?> =
        store.map { it[userId to id] }

    override suspend fun setLastFiredAt(id: String, userId: String, lastFiredAt: Long, updatedAt: Long) {
        store.update { m ->
            m[(userId to id)]?.let { e ->
                m + (
                    (userId to id) to e.copy(
                        lastFiredAt = lastFiredAt,
                        updatedAt = updatedAt,
                    )
                )
            }
                ?: m
        }
    }
}

private class FakeReminderDao(
    private val store:
        MutableStateFlow<Map<Pair<String, String>, com.singularity.todo.core.database.TaskReminderEntity>>,
) : ReminderDao {

    override fun watchAll(userId: String): Flow<List<com.singularity.todo.core.database.TaskReminderEntity>> =
        store.map { it.values.filter { r -> r.userId == userId }.sortedBy { it.fireAt } }

    override fun watchByTask(
        taskId: String,
        userId: String,
    ): Flow<List<com.singularity.todo.core.database.TaskReminderEntity>> =
        store.map { it.values.filter { r -> r.taskId == taskId && r.userId == userId }.sortedBy { it.fireAt } }

    override fun getDueBefore(
        now: Long,
        userId: String,
    ): Flow<List<com.singularity.todo.core.database.TaskReminderEntity>> =
        store.map { it.values.filter { r -> r.fireAt <= now && r.userId == userId }.sortedBy { it.fireAt } }

    override suspend fun upsert(reminder: com.singularity.todo.core.database.TaskReminderEntity) {
        store.update { it + ((reminder.userId to reminder.id) to reminder) }
    }

    override fun getRecentDueBefore(
        now: Long,
        userId: String,
        limit: Int,
    ): Flow<List<com.singularity.todo.core.database.TaskReminderEntity>> = store.map {
        it.values.filter { r -> r.fireAt <= now && r.userId == userId }.sortedBy { r -> -r.fireAt }.take(
            limit,
        )
    }

    override suspend fun delete(id: String, userId: String) {
        store.update { it - (userId to id) }
    }

    override suspend fun deleteByTask(taskId: String, userId: String) {
        store.update { current ->
            current.filterValues { r -> r.userId != userId || r.taskId != taskId }
        }
    }

    override suspend fun getById(id: String, userId: String): com.singularity.todo.core.database.TaskReminderEntity? =
        store.value[userId to id]

    override fun watchByIdForUser(
        id: String,
        userId: String,
    ): Flow<com.singularity.todo.core.database.TaskReminderEntity?> = store.map { it[userId to id] }

    override fun watchRecurringTaskIds(userId: String): Flow<List<String>> = store.map { map ->
        map.values.filter { r -> r.userId == userId && r.recurringPattern != null }.map { it.taskId }.distinct()
    }

    override suspend fun setLastFiredAt(id: String, userId: String, lastFiredAt: Long, updatedAt: Long) {
        store.update { current ->
            val key = userId to id
            val existing = current[key] ?: return@update current
            current + (key to existing.copy(lastFiredAt = lastFiredAt, updatedAt = updatedAt))
        }
    }
}

// ─── CalendarSyncTaskMapDao ───────────────────────────────────────────────────────

private class FakeCalendarSyncTaskMapDao(private val store: MutableStateFlow<Map<String, CalendarSyncTaskMapEntity>>) :
    CalendarSyncTaskMapDao {

    override fun observeAll(userId: String): Flow<List<CalendarSyncTaskMapEntity>> =
        store.map { vals -> vals.values.filter { it.userId == userId } }

    override suspend fun getAll(userId: String): List<CalendarSyncTaskMapEntity> =
        store.value.values.filter { it.userId == userId }

    override suspend fun getEventId(taskId: String, userId: String): Long? =
        store.value[taskId]?.takeIf { it.userId == userId }?.eventId

    override suspend fun getByTaskId(taskId: String, userId: String): CalendarSyncTaskMapEntity? =
        store.value[taskId]?.takeIf { it.userId == userId }

    override suspend fun upsert(entity: CalendarSyncTaskMapEntity) {
        store.update { it + (entity.taskId to entity) }
    }

    override suspend fun delete(taskId: String, userId: String) {
        store.update { current ->
            // Only delete if the row belongs to this user
            if (current[taskId]?.userId == userId) current - taskId else current
        }
    }

    override suspend fun deleteByEventId(eventId: Long, userId: String) {
        store.update { current ->
            current.filterValues { it.eventId != eventId || it.userId != userId }
        }
    }

    override suspend fun deleteStale(taskIds: List<String>, userId: String) {
        store.update { current ->
            // Only keep rows that either (a) are for a different user, or (b) are in the taskIds set
            current.filterValues { it.userId != userId || it.taskId in taskIds }
        }
    }

    override suspend fun clearAll(userId: String) {
        store.update { current ->
            current.filterValues { it.userId != userId }
        }
    }

    override suspend fun deleteLegacyRows() {
        store.update { current ->
            current.filterValues { it.userId != null }
        }
    }
}

// ─── ChecklistDao ───────────────────────────────────────────────────────────────

private class FakeChecklistDao(private val store: MutableStateFlow<Map<String, ChecklistItemEntity>>) : ChecklistDao {

    override fun watchByTask(taskId: String): Flow<List<ChecklistItemEntity>> =
        store.map { it.values.filter { c -> c.taskId == taskId }.sortedBy { c -> c.sortOrder } }

    override suspend fun upsert(item: ChecklistItemEntity) {
        store.update { it + (item.id to item) }
    }

    /**
     * The real DAO scopes these through the owning task's `user_id`. This fake
     * has no task store, so it cannot evaluate ownership and applies the
     * deletion, reporting how many rows it removed.
     */
    override suspend fun deleteForUser(id: String, userId: String): Int {
        val existed = store.value.containsKey(id)
        store.update { it - id }
        return if (existed) 1 else 0
    }

    override suspend fun deleteByTaskForUser(taskId: String, userId: String): Int {
        val before = store.value.values.count { it.taskId == taskId }
        store.update { current -> current.filterValues { c -> c.taskId != taskId } }
        return before
    }

    override suspend fun updateCompletionStatus(
        itemId: String,
        isCompleted: Boolean,
        updatedAt: Long,
        userId: String,
    ): Int {
        val existing = store.value[itemId] ?: return 0
        store.update { it + (itemId to existing.copy(isCompleted = isCompleted, updatedAt = updatedAt)) }
        return 1
    }
}

// ─── LlmUsageDao ────────────────────────────────────────────────────────────────

private class FakeLlmUsageDao(private val store: MutableStateFlow<Map<String, LlmUsageEntity>>) : LlmUsageDao {

    override suspend fun upsert(entity: LlmUsageEntity) {
        store.update { it + (entity.id to entity) }
    }

    override fun observeRecent(profileId: String, limit: Int): Flow<List<LlmUsageEntity>> =
        store.map { it.values.filter { e -> e.profileId == profileId }.sortedByDescending { it.createdAt }.take(limit) }

    override fun observeByDay(
        profileId: String,
        sinceEpochMs: Long,
    ): Flow<List<com.singularity.todo.core.database.DailyUsageRow>> = store.map { rows ->
        rows.values
            .filter { it.profileId == profileId && it.createdAt >= sinceEpochMs }
            .groupBy { java.time.Instant.ofEpochMilli(it.createdAt).toString().take(10) }
            .map { (date, items) ->
                com.singularity.todo.core.database.DailyUsageRow(
                    date = date,
                    totalTokens = items.sumOf { it.totalTokens }.toLong(),
                    totalCostMicros = items.mapNotNull { it.costUsdMicros }.takeIf { it.isNotEmpty() }?.sum(),
                    callCount = items.size.toLong(),
                )
            }
            .sortedByDescending { it.date }
    }

    override fun observeByTool(profileId: String): Flow<List<com.singularity.todo.core.database.ToolUsageRow>> =
        store.map { rows ->
            rows.values
                .filter { it.profileId == profileId }
                .groupBy { it.toolName }
                .map { (tool, items) ->
                    com.singularity.todo.core.database.ToolUsageRow(
                        toolName = tool,
                        totalTokens = items.sumOf { it.totalTokens }.toLong(),
                        totalCostMicros = items.mapNotNull { it.costUsdMicros }.takeIf { it.isNotEmpty() }?.sum(),
                        callCount = items.size.toLong(),
                    )
                }
                .sortedByDescending { it.totalTokens }
        }

    override fun observeByModel(profileId: String): Flow<List<com.singularity.todo.core.database.ModelUsageRow>> =
        store.map { rows ->
            rows.values
                .filter { it.profileId == profileId }
                .groupBy { it.modelId }
                .map { (model, items) ->
                    com.singularity.todo.core.database.ModelUsageRow(
                        modelId = model,
                        totalTokens = items.sumOf { it.totalTokens }.toLong(),
                        totalCostMicros = items.mapNotNull { it.costUsdMicros }.takeIf { it.isNotEmpty() }?.sum(),
                        callCount = items.size.toLong(),
                    )
                }
                .sortedByDescending { it.totalTokens }
        }

    override suspend fun pruneOlderThan(cutoffEpochMs: Long) {
        store.update { it.filterValues { e -> e.createdAt >= cutoffEpochMs } }
    }
}

// ─── ProfileDao ─────────────────────────────────────────────────────────────────

private class FakeProfileDao(private val store: MutableStateFlow<Map<String, ProfileEntity>>) : ProfileDao {

    override fun all(): Flow<List<ProfileEntity>> = store.map { it.values.sortedBy { p -> p.createdAt } }

    override suspend fun getById(id: String): ProfileEntity? = store.value[id]

    override suspend fun getDefault(): ProfileEntity? = store.value.values.find { it.isDefault }

    override suspend fun allNames(): List<String> = store.value.keys.toList()

    override suspend fun upsert(profile: ProfileEntity) {
        store.update { it + (profile.id to profile) }
    }

    override suspend fun deleteById(id: String) {
        store.update { it - id }
    }

    override suspend fun count(): Int = store.value.size
}

// ─── AgendaViewDao ────────────────────────────────────────────────────────────

private class FakeAgendaViewDao(private val store: MutableStateFlow<Map<String, AgendaViewEntity>>) : AgendaViewDao {

    override fun watchAll(userId: String): Flow<List<AgendaViewEntity>> = store.map {
        it.values.filter { v -> v.userId == userId }.sortedBy { v -> v.name }
    }

    override fun watchById(userId: String, id: String): Flow<AgendaViewEntity?> = store.map {
        it.values.find { v -> v.userId == userId && v.id == id }
    }

    override suspend fun getById(userId: String, id: String): AgendaViewEntity? =
        store.value.values.find { v -> v.userId == userId && v.id == id }

    override suspend fun upsert(entity: AgendaViewEntity) {
        store.update { it + (entity.id to entity) }
    }

    override suspend fun delete(userId: String, id: String) {
        store.update { current ->
            current.filterValues { v -> !(v.userId == userId && v.id == id) }
        }
    }
}

// ─── RemoteConfigDao ────────────────────────────────────────────────────────

private class FakeRemoteConfigDao(private val store: MutableStateFlow<Map<String, RemoteConfigEntity>>) :
    RemoteConfigDao {

    override fun watchDefault(): Flow<RemoteConfigEntity?> = store.map { it["default"] }

    override suspend fun getDefault(): RemoteConfigEntity? = store.value["default"]

    override suspend fun upsert(entity: RemoteConfigEntity) {
        store.update { it + (entity.id to entity) }
    }

    override suspend fun deleteDefault() {
        store.update { it - "default" }
    }
}

// ─── RemoteConfigCacheDao ─────────────────────────────────────────────────────

private class FakeRemoteConfigCacheDao(private val store: MutableStateFlow<RemoteConfigCacheEntity?>) :
    RemoteConfigCacheDao {

    override fun watchDefault(): Flow<RemoteConfigCacheEntity?> = store

    override suspend fun getDefault(): RemoteConfigCacheEntity? = store.value

    override suspend fun upsert(entity: RemoteConfigCacheEntity) {
        store.value = entity
    }

    override suspend fun deleteDefault() {
        store.value = null
    }
}

// ─── SavedSearchDao ────────────────────────────────────────────────────────────

private class FakeSavedSearchDao(private val store: MutableStateFlow<Map<String, SavedSearchEntity>>) : SavedSearchDao {

    override fun watchAll(userId: String): Flow<List<SavedSearchEntity>> = store.map {
        it.values.filter { v -> v.userId == userId }.sortedBy { v -> v.name }
    }

    override fun watchById(userId: String, id: String): Flow<SavedSearchEntity?> = store.map {
        it.values.find { v -> v.userId == userId && v.id == id }
    }

    override suspend fun getById(userId: String, id: String): SavedSearchEntity? =
        store.value.values.find { v -> v.userId == userId && v.id == id }

    override suspend fun findByName(userId: String, name: String): SavedSearchEntity? =
        store.value.values.find { v -> v.userId == userId && v.name.equals(name, ignoreCase = true) }

    override suspend fun upsert(entity: SavedSearchEntity) {
        store.update { it + (entity.id to entity) }
    }

    override suspend fun delete(userId: String, id: String) {
        store.update { current ->
            current.filterValues { v -> !(v.userId == userId && v.id == id) }
        }
    }
}

// ─── TagGroupDao ───────────────────────────────────────────────────────────────

private class FakeTagGroupDao(private val store: MutableStateFlow<Map<String, TagGroupEntity>>) : TagGroupDao {
    override fun watchAll(userId: String): Flow<List<TagGroupEntity>> = store.map {
        it.values.filter { t -> t.userId == userId }.sortedBy { t -> t.name }
    }
    override fun watchById(id: String): Flow<TagGroupEntity?> = store.map { it[id] }
    override fun watchByIdForUser(id: String, userId: String): Flow<TagGroupEntity?> = store.map {
        it[id]?.takeIf { t -> t.userId == userId }
    }
    override suspend fun getByIdForUser(id: String, userId: String): TagGroupEntity? =
        store.value.values.find { it.id == id && it.userId == userId }
    override suspend fun upsert(entity: TagGroupEntity) {
        store.update { it + (entity.id to entity) }
    }
    override suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int {
        val entity = store.value[id]
        return if (entity != null && entity.userId == userId) {
            store.update { current ->
                val existing = current[id] ?: return@update current
                current + (id to existing.copy(deletedAt = ts, updatedAt = ts))
            }
            1
        } else {
            0
        }
    }
}

// ─── ProjectInheritedTagGroupDao ───────────────────────────────────────────────

private class FakeProjectInheritedTagGroupDao(
    private val store: MutableStateFlow<List<ProjectInheritedTagGroupCrossRef>>,
) : ProjectInheritedTagGroupDao {
    override fun watchByProject(projectId: String, userId: String): Flow<List<String>> = store.map { list ->
        list.filter { it.projectId == projectId }.map { it.tagGroupId }
    }

    /**
     * The real DAO scopes this through the owning project's `user_id`. This fake
     * holds only the join rows, so ownership cannot be evaluated here.
     */
    override suspend fun deleteAllForUser(projectId: String, userId: String): Int {
        val before = store.value.count { it.projectId == projectId }
        store.update { list -> list.filter { it.projectId != projectId } }
        return before
    }

    /**
     * The real DAO inserts only when the owning project belongs to [userId]. This
     * fake holds just the join rows and has no view of the projects table, so it
     * applies the insert — the ownership contract is exercised against real
     * SQLite in the repository sync tests instead.
     */
    override suspend fun insertForUser(projectId: String, tagGroupId: String, userId: String) {
        store.update { list ->
            if (list.any { it.projectId == projectId && it.tagGroupId == tagGroupId }) {
                list
            } else {
                list + ProjectInheritedTagGroupCrossRef(projectId = projectId, tagGroupId = tagGroupId)
            }
        }
    }

    /** No projects table in this fake, so ownership cannot be evaluated. */
    override suspend fun isProjectOwnedBy(projectId: String, userId: String): Boolean = true

    /**
     * Mirrors the real DAO minus the `projects.user_id` sub-select — this fake holds
     * only the join rows, so a group id is removed from every project that has it.
     */
    override suspend fun deleteByGroupForUser(tagGroupId: String, userId: String): Int {
        val before = store.value.count { it.tagGroupId == tagGroupId }
        store.update { list -> list.filter { it.tagGroupId != tagGroupId } }
        return before
    }
}

private class FakeTimeEntryDao(private val store: MutableStateFlow<Map<String, TimeEntryEntity>>) : TimeEntryDao {
    override fun watchForTask(taskId: String): Flow<List<TimeEntryEntity>> =
        store.map { map -> map.values.filter { it.taskId == taskId && it.deletedAt == null }.sortedByDescending { it.startedAt } }

    override fun watchOpenEntry(userId: String): Flow<TimeEntryEntity?> =
        store.map { map -> map.values.find { it.userId == userId && it.endedAt == null && it.deletedAt == null } }

    override suspend fun getOpenEntry(userId: String): TimeEntryEntity? =
        store.value.values.find { it.userId == userId && it.endedAt == null && it.deletedAt == null }

    override fun watchForUserInRange(userId: String, startMs: Long, endMs: Long): Flow<List<TimeEntryEntity>> =
        store.map { map ->
            map.values.filter {
                it.userId == userId &&
                    it.startedAt >= startMs &&
                    it.startedAt < endMs &&
                    it.deletedAt == null
            }.sortedByDescending { it.startedAt }
        }

    override suspend fun upsert(entity: TimeEntryEntity) {
        store.update { map -> map + (entity.id to entity) }
    }

    override suspend fun stopEntry(id: String, endedAt: Long, updatedAt: Long, userId: String): Int {
        val before = store.value[id]
        if (before == null || before.userId != userId) return 0
        store.update { map -> map + (id to before.copy(endedAt = endedAt, updatedAt = updatedAt)) }
        return 1
    }

    override suspend fun updateNote(id: String, note: String?, updatedAt: Long, userId: String): Int {
        val before = store.value[id] ?: return 0
        if (before.userId != userId) return 0
        store.update { map -> map + (id to before.copy(note = note, updatedAt = updatedAt)) }
        return 1
    }

    override suspend fun softDelete(id: String, deletedAt: Long, userId: String): Int {
        val before = store.value[id] ?: return 0
        if (before.userId != userId) return 0
        store.update { map -> map + (id to before.copy(deletedAt = deletedAt, updatedAt = deletedAt)) }
        return 1
    }

    override suspend fun delete(id: String, userId: String): Int {
        val before = store.value[id] ?: return 0
        if (before.userId != userId) return 0
        store.update { map -> map - id }
        return 1
    }
}
