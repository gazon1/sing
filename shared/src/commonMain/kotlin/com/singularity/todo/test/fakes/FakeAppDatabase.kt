package com.singularity.todo.test.fakes

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationEntity
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
import com.singularity.todo.core.database.OwnerEraseDao
import com.singularity.todo.core.database.OwnerScopeDao
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
import com.singularity.todo.core.database.TaskReminderEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.sync.RemoteConfigDao
import com.singularity.todo.core.sync.RemoteConfigEntity
import com.singularity.todo.core.sync.SyncOutboxDao
import com.singularity.todo.core.sync.SyncDeadLetterDao
import com.singularity.todo.core.sync.SyncDeadLetterEntity
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncStateDao
import com.singularity.todo.core.sync.SyncShadowDao
import com.singularity.todo.core.sync.SyncShadowEntity
import com.singularity.todo.core.sync.SyncStateEntity
import com.singularity.todo.core.sync.SyncOutboxEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventDao
import com.singularity.todo.feature.calendar_sync.data.CalendarImportEventEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncStateDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncStateEntity
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapEntity
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowDao
import com.singularity.todo.feature.calendar_sync.data.GoogleEventShadowEntity
import com.singularity.todo.feature.proposals.data.AiProposalEntity
import com.singularity.todo.feature.proposals.data.ProposalDao
import com.singularity.todo.feature.proposals.data.ProposalItemDao
import com.singularity.todo.feature.proposals.data.ProposalItemEntity
import com.singularity.todo.feature.timetracking.data.TimeEntryDao
import com.singularity.todo.feature.timetracking.data.TimeEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

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

    @Suppress("BackingPropertyNaming")
    private val _outbox = MutableStateFlow<Map<String, SyncOutboxEntity>>(emptyMap())

    @Suppress("BackingPropertyNaming")
    private val _deadLetter = MutableStateFlow<Map<String, SyncDeadLetterEntity>>(emptyMap())

    @Suppress("BackingPropertyNaming")
    private val _syncState = MutableStateFlow<Map<SyncScope, SyncStateEntity>>(emptyMap())

    @Suppress("BackingPropertyNaming")
    private val _syncShadow = MutableStateFlow<Map<SyncShadowKey, SyncShadowEntity>>(emptyMap())
    private val _attachments = MutableStateFlow<Map<String, AttachmentEntity>>(emptyMap())
    private val _annotations =
        MutableStateFlow<Map<String, AttachmentAnnotationEntity>>(emptyMap())
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

    // Google Calendar sync. Keyed the way the real primary keys are — by user first —
    // so a fake that let one profile see another's row would hide exactly the bug the
    // composite key exists to prevent.
    private val _calendarSyncState =
        MutableStateFlow<Map<Triple<String, String, String>, CalendarSyncStateEntity>>(emptyMap())
    private val _googleEventShadow =
        MutableStateFlow<Map<Pair<String, String>, GoogleEventShadowEntity>>(emptyMap())
    private val _calendarImportEvents =
        MutableStateFlow<Map<Pair<String, String>, CalendarImportEventEntity>>(emptyMap())
    private val _savedSearches = MutableStateFlow<Map<String, SavedSearchEntity>>(emptyMap())
    private val _tagGroups = MutableStateFlow<Map<String, TagGroupEntity>>(emptyMap())
    private val _projectTagGroups = MutableStateFlow<List<ProjectInheritedTagGroupCrossRef>>(emptyList())

    @Suppress("BackingPropertyNaming") // Internal store, no matching public property
    private val _timeEntries = MutableStateFlow<Map<String, TimeEntryEntity>>(emptyMap())

    @Suppress("BackingPropertyNaming")
    private val _proposals = MutableStateFlow<Map<String, AiProposalEntity>>(emptyMap())

    @Suppress("BackingPropertyNaming")
    private val _proposalItems = MutableStateFlow<Map<String, ProposalItemEntity>>(emptyMap())

    override fun taskDao(): TaskDao = FakeTaskDao(_tasks, _taskTags, _taskDependencies, _tags, _projectTagGroups)
    override fun noteDao(): NoteDao = FakeNoteDao(_notes)
    override fun projectDao(): ProjectDao = FakeProjectDao(_projects)
    override fun tagDao(): TagDao = FakeTagDao(_tags)
    override fun syncOutboxDao(): SyncOutboxDao = FakeSyncOutboxDao(_outbox)
    override fun syncDeadLetterDao(): SyncDeadLetterDao = FakeSyncDeadLetterDao(_deadLetter)
    override fun syncStateDao(): SyncStateDao = FakeSyncStateDao(_syncState)
    override fun syncShadowDao(): SyncShadowDao = FakeSyncShadowDao(_syncShadow)
    override fun attachmentDao(): AttachmentDao = FakeAttachmentDao(_attachments)
    override fun annotationDao(): AttachmentAnnotationDao = FakeAttachmentAnnotationDao(_annotations)
    override fun reminderDao(): ReminderDao = FakeReminderDao(_reminders)
    override fun projectReminderDao(): ProjectReminderDao = FakeProjectReminderDao(_projectReminders)
    override fun checklistDao(): ChecklistDao = FakeChecklistDao(_checklist)
    override fun llmUsageDao(): LlmUsageDao = FakeLlmUsageDao(_llmUsage)
    override fun profileDao(): ProfileDao = FakeProfileDao(_profiles)
    override fun agendaViewDao(): AgendaViewDao = FakeAgendaViewDao(_agendaViews)
    override fun remoteConfigDao(): RemoteConfigDao = FakeRemoteConfigDao(_remoteConfigs)
    override fun remoteConfigCacheDao(): RemoteConfigCacheDao = FakeRemoteConfigCacheDao(_remoteConfigCache)
    override fun calendarSyncTaskMapDao(): CalendarSyncTaskMapDao = FakeCalendarSyncTaskMapDao(_calendarSyncTaskMap)
    override fun calendarSyncStateDao(): CalendarSyncStateDao = FakeCalendarSyncStateDao(_calendarSyncState)
    override fun googleEventShadowDao(): GoogleEventShadowDao = FakeGoogleEventShadowDao(_googleEventShadow)
    override fun calendarImportEventDao(): CalendarImportEventDao =
        FakeCalendarImportEventDao(_calendarImportEvents)
    override fun savedSearchDao(): SavedSearchDao = FakeSavedSearchDao(_savedSearches)
    override fun tagGroupDao(): TagGroupDao = FakeTagGroupDao(_tagGroups)
    override fun projectInheritedTagGroupDao(): ProjectInheritedTagGroupDao = FakeProjectInheritedTagGroupDao(
        _projectTagGroups,
    )
    override fun timeEntryDao(): TimeEntryDao = FakeTimeEntryDao(_timeEntries)
    override fun proposalDao(): ProposalDao = FakeProposalDao(_proposals)
    override fun proposalItemDao(): ProposalItemDao = FakeProposalItemDao(_proposals, _proposalItems)

    override fun ownerEraseDao(): OwnerEraseDao = FakeOwnerEraseDao(
        tasks = _tasks,
        taskTags = _taskTags,
        taskDependencies = _taskDependencies,
        checklist = _checklist,
        projects = _projects,
        projectTagGroups = _projectTagGroups,
        notes = _notes,
        tags = _tags,
        tagGroups = _tagGroups,
        taskReminders = _reminders,
        projectReminders = _projectReminders,
    )

    override fun ownerScopeDao(): OwnerScopeDao = FakeOwnerScopeDao(
        tasks = _tasks,
        projects = _projects,
    )

    override suspend fun clearAllTables() {
        _tasks.value = emptyMap()
        _taskTags.value = emptyList()
        _taskDependencies.value = emptyList()
        _notes.value = emptyMap()
        _projects.value = emptyMap()
        _tags.value = emptyMap()
        _outbox.value = emptyMap()
        _attachments.value = emptyMap()
        _annotations.value = emptyMap()
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
        _proposals.value = emptyMap()
        _proposalItems.value = emptyMap()
    }

    // ─── Seed helpers ────────────────────────────────────────────────────────
    // Each is a single mutation, no fixture DSL — easier to read in test setup.

    fun seedTasks(items: List<TaskEntity>) {
        _tasks.value = items.associateBy { it.id }
    }

    /**
     * Tag cross-references, as a list rather than keyed by anything — the table's key is
     * the pair, and the fake's child deletes filter through it by `taskId`.
     */
    fun seedTagRefs(items: List<TaskTagCrossRef>) {
        _taskTags.value = items
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
    fun seedAnnotations(items: List<AttachmentAnnotationEntity>) {
        _annotations.value = items.associateBy { it.id }
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
    private val tags: MutableStateFlow<Map<String, TagEntity>>,
    private val projectTagGroups: MutableStateFlow<List<ProjectInheritedTagGroupCrossRef>>,
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
        kotlinx.coroutines.flow.combine(store, crossRefs, tags, projectTagGroups) { tasks, refs, tagsMap, ptgRefs ->
            val directlyTagged = refs.filter { it.tagId == tagId }.map { it.taskId }.toSet()
            val inheritedViaProject = ptgRefs
                .filter { ptg -> tagsMap.values.any { t -> t.groupId == ptg.tagGroupId && t.id == tagId } }
                .mapNotNull { ptg -> tasks.values.find { t -> t.projectId == ptg.projectId }?.id }
                .toSet()
            val allIds = directlyTagged + inheritedViaProject
            tasks.values.filter { t ->
                t.userId == userId && t.archivedAt == null && t.id in allIds
            }.sortedWith(compareBy({ it.dueDate ?: "\uFFFF" }, { !it.isPinned }))
        }

    override fun watchByAnyTag(userId: String, tagIds: List<String>): Flow<List<TaskEntity>> =
        kotlinx.coroutines.flow.combine(store, crossRefs, tags, projectTagGroups) { tasks, refs, tagsMap, ptgRefs ->
            val directlyTagged = refs.filter { it.tagId in tagIds }.map { it.taskId }.toSet()
            val inheritedViaProject = ptgRefs
                .filter { ptg -> tagsMap.values.any { t -> t.groupId == ptg.tagGroupId && t.id in tagIds } }
                .mapNotNull { ptg -> tasks.values.find { t -> t.projectId == ptg.projectId }?.id }
                .toSet()
            val allIds = directlyTagged + inheritedViaProject
            tasks.values.filter { t ->
                t.userId == userId && t.archivedAt == null && t.id in allIds
            }.sortedWith(compareBy({ it.dueDate ?: "\uFFFF" }, { !it.isPinned }))
        }

    override fun watchByAllTags(userId: String, tagIds: List<String>, size: Int): Flow<List<TaskEntity>> =
        kotlinx.coroutines.flow.combine(store, crossRefs, tags, projectTagGroups) { tasks, refs, tagsMap, ptgRefs ->
            // Build per-task set of tags: direct + inherited via the task's project
            val taskTagSets = mutableMapOf<String, MutableSet<String>>()
            for (t in tasks.values) {
                if (t.archivedAt != null || t.userId != userId) continue
                val direct = refs.filter { it.taskId == t.id && it.tagId in tagIds }.map { it.tagId }.toMutableSet()
                val inherited = mutableSetOf<String>()
                t.projectId?.let { pid ->
                    val inheritedGroupIds = ptgRefs.filter { it.projectId == pid }.map { it.tagGroupId }.toSet()
                    tagsMap.values
                        .filter { it.groupId in inheritedGroupIds && it.id in tagIds }
                        .forEach { inherited.add(it.id) }
                }
                taskTagSets[t.id] = (direct + inherited).toMutableSet()
            }
            val matchingIds = taskTagSets.entries
                .filter { it.value.size == tagIds.toSet().size && tagIds.all { id -> id in it.value } }
                .map { it.key }
                .toSet()
            tasks.values.filter { t ->
                t.userId == userId && t.archivedAt == null && t.id in matchingIds
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
    override suspend fun touchUpdatedAtForUser(id: String, ts: Long, userId: String): Int =
        mutateForUser(id, userId) { it.copy(updatedAt = ts) }
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

    override suspend fun restoreForUser(id: String, ts: Long, userId: String): Int {
        val entity = store.value[id]
        return if (entity != null && entity.userId == userId && entity.deletedAt != null) {
            mutate(id) { it.copy(deletedAt = null, updatedAt = ts) }
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

    /**
     * Honours both filters the real query does — the owner's, and the backoff's.
     *
     * The owner filter is not decoration. This fake implemented only the backoff one
     * for its whole life, which means a test could put two accounts' rows in one table
     * and still see a "pending" list containing both: precisely the state REQ-UA-019
     * says must not arise, and precisely what a fake that quietly drops half a query's
     * conditions will let a test assert as correct.
     */
    override suspend fun getPending(now: Long, ownerId: String): List<SyncOutboxEntity> =
        store.value.values
            .filter { it.ownerId == ownerId }
            .filter { it.nextAttemptAt == null || it.nextAttemptAt <= now }
            .sortedBy { it.createdAt }

    override suspend fun countPendingFor(ownerId: String): Int =
        store.value.values.count { it.ownerId == ownerId }

    override suspend fun insert(entity: SyncOutboxEntity) {
        store.update { it + (entity.patchId to entity) }
    }
    override suspend fun delete(id: String) {
        store.update { it - id }
    }
    override suspend fun markFailed(id: String, error: String, nextAttemptAt: Long) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (
                id to existing.copy(
                    attempts = existing.attempts + 1,
                    lastError = error,
                    nextAttemptAt = nextAttemptAt,
                )
            )
        }
    }
    override suspend fun attemptsOf(id: String): Int? = store.value[id]?.attempts

    override suspend fun deleteByEntity(ownerId: String, entityId: String) {
        store.update { it.filterValues { e -> e.ownerId != ownerId || e.entityId != entityId } }
    }

    /** `DELETE FROM sync_outbox WHERE owner_id = :ownerId` — every profile's patches of that owner. */
    override suspend fun deleteForOwner(ownerId: String): Int {
        val doomed = store.value.values.filter { it.ownerId == ownerId }
        store.update { current -> current.filterValues { it.ownerId != ownerId } }
        return doomed.size
    }

    override suspend fun clearAll() {
        store.value = emptyMap()
    }
}

private class FakeSyncDeadLetterDao(private val store: MutableStateFlow<Map<String, SyncDeadLetterEntity>>) :
    SyncDeadLetterDao {

    override fun watch(): Flow<List<SyncDeadLetterEntity>> =
        store.map { it.values.sortedByDescending { e -> e.failedAt } }

    override fun watchCount(): Flow<Int> = store.map { it.size }

    override suspend fun getAll(): List<SyncDeadLetterEntity> =
        store.value.values.sortedByDescending { it.failedAt }

    override suspend fun insert(entity: SyncDeadLetterEntity) {
        store.update { it + (entity.patchId to entity) }
    }

    override suspend fun delete(id: String): Int {
        val present = store.value.containsKey(id)
        store.update { it - id }
        return if (present) 1 else 0
    }

    /** `DELETE FROM sync_dead_letter WHERE owner_id = :ownerId` — the shelf holds several owners at once. */
    override suspend fun deleteForOwner(ownerId: String): Int {
        val doomed = store.value.values.filter { it.ownerId == ownerId }
        store.update { current -> current.filterValues { it.ownerId != ownerId } }
        return doomed.size
    }

    override suspend fun clearAll() {
        store.value = emptyMap()
    }
}

/**
 * In-memory [SyncStateDao], keyed by (owner, profile).
 *
 * The composite key is the point of the class, so it is modelled as a real map key
 * rather than a filter over a flat row list — a fake that cannot express the
 * distinction is a fake that cannot test the thing the table exists for.
 */
private class FakeSyncStateDao(private val store: MutableStateFlow<Map<SyncScope, SyncStateEntity>>) : SyncStateDao {

    private fun key(ownerId: String, profileId: String) = SyncScope(ownerId, profileId)

    override suspend fun get(ownerId: String, profileId: String): SyncStateEntity? =
        store.value[key(ownerId, profileId)]

    override fun observe(ownerId: String, profileId: String): Flow<SyncStateEntity?> =
        store.map { it[key(ownerId, profileId)] }

    override suspend fun insertIfAbsent(entity: SyncStateEntity) {
        val k = SyncScope(entity.ownerId, entity.profileId)
        store.update { if (k in it) it else it + (k to entity) }
    }

    override suspend fun setLastLsn(ownerId: String, profileId: String, lsn: Long) =
        mutate(ownerId, profileId) { it.copy(lastLsn = lsn) }

    override suspend fun setLastSuccessfulSyncAt(ownerId: String, profileId: String, at: Long) =
        mutate(ownerId, profileId) { it.copy(lastSuccessfulSyncAt = at) }

    override suspend fun setDeviceId(ownerId: String, profileId: String, deviceId: String) =
        mutate(ownerId, profileId) { it.copy(deviceId = deviceId) }

    override suspend fun setAutoSyncEnabled(ownerId: String, profileId: String, enabled: Boolean) =
        mutate(ownerId, profileId) { it.copy(autoSyncEnabled = enabled) }

    override suspend fun setScheduledIntervalMinutes(ownerId: String, profileId: String, minutes: Int) =
        mutate(ownerId, profileId) { it.copy(scheduledIntervalMinutes = minutes) }

    override suspend fun setEnabledTriggers(ownerId: String, profileId: String, triggers: String) =
        mutate(ownerId, profileId) { it.copy(enabledTriggers = triggers) }

    override suspend fun setSeedCompleted(ownerId: String, profileId: String, completed: Boolean) =
        mutate(ownerId, profileId) { it.copy(seedCompleted = completed) }

    override suspend fun setAttachmentsSyncEnabled(ownerId: String, profileId: String, enabled: Boolean) =
        mutate(ownerId, profileId) { it.copy(attachmentsSyncEnabled = enabled) }

    override suspend fun clearAll() {
        store.value = emptyMap()
    }

    private fun mutate(ownerId: String, profileId: String, fn: (SyncStateEntity) -> SyncStateEntity) {
        val k = key(ownerId, profileId)
        store.update { current ->
            val existing = current[k] ?: SyncStateEntity(ownerId = ownerId, profileId = profileId)
            current + (k to fn(existing))
        }
    }
}

/**
 * Composite key of [SyncShadowEntity], modelled as a real key rather than a filter.
 *
 * Two entities of the same type under two profiles share a type and nothing else;
 * a fake that stored rows in a list keyed by entity id could not tell them apart.
 */
private data class SyncShadowKey(
    val ownerId: String,
    val profileId: String,
    val entityType: String,
    val entityId: String,
)

/**
 * In-memory [SyncShadowDao].
 *
 * The `AND in_flight_patch_id = :patchId` guard on [confirm] and [release] is
 * reproduced rather than approximated: it is the entire reason a stale response
 * cannot promote a superseded patch's state, and a fake without it would let a test
 * assert that the guard works.
 */
private class FakeSyncShadowDao(private val store: MutableStateFlow<Map<SyncShadowKey, SyncShadowEntity>>) :
    SyncShadowDao {

    private fun key(ownerId: String, profileId: String, entityType: String, entityId: String) =
        SyncShadowKey(ownerId, profileId, entityType, entityId)

    override suspend fun get(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
    ): SyncShadowEntity? = store.value[key(ownerId, profileId, entityType, entityId)]

    override suspend fun upsert(entity: SyncShadowEntity) {
        val k = key(entity.ownerId, entity.profileId, entity.entityType, entity.entityId)
        store.value = store.value + (k to entity)
    }

    override suspend fun confirm(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
        json: String,
        serverVersion: Long?,
    ): Int = mutateIfOwned(ownerId, profileId, entityType, entityId, patchId) { row ->
        // The COALESCE in the real query, reproduced: a response with no version must
        // not reset one the client already had, or the next patch claims the server has
        // never seen the row.
        row.copy(
            confirmedJson = json,
            inFlightJson = null,
            inFlightPatchId = null,
            serverVersion = serverVersion ?: row.serverVersion,
        )
    }

    override suspend fun release(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
    ): Int = mutateIfOwned(ownerId, profileId, entityType, entityId, patchId) { row ->
        row.copy(inFlightJson = null, inFlightPatchId = null)
    }

    /**
     * Applies [transform] only while [patchId] still owns the in-flight marker, and
     * reports whether it did — the same contract the SQL guard gives.
     */
    private fun mutateIfOwned(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
        transform: (SyncShadowEntity) -> SyncShadowEntity,
    ): Int {
        val k = key(ownerId, profileId, entityType, entityId)
        val row = store.value[k]
        if (row == null || row.inFlightPatchId != patchId) return 0
        store.value = store.value + (k to transform(row))
        return 1
    }

    override suspend fun clearScope(ownerId: String, profileId: String) {
        store.update { current -> current.filterKeys { it.ownerId != ownerId || it.profileId != profileId } }
    }

    /**
     * `DELETE FROM sync_shadow WHERE owner_id = :ownerId`.
     *
     * Deliberately not scoped by profile, unlike [clearScope]: the real query takes every
     * profile of the owner, and a leftover shadow on another profile would make that
     * profile's next sync believe the server confirmed rows the device no longer holds.
     */
    override suspend fun deleteForOwner(ownerId: String): Int {
        val doomed = store.value.count { it.key.ownerId == ownerId }
        store.update { current -> current.filterKeys { it.ownerId != ownerId } }
        return doomed
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

// ─── AttachmentAnnotationDao ──────────────────────────────────────────────────

/**
 * In-memory stand-in for [AttachmentAnnotationDao].
 *
 * Mirrors the real predicates rather than returning the whole store: a fake that dropped
 * the `user_id` filter would let a repository bug pass here and fail on a device.
 */
private class FakeAttachmentAnnotationDao(
    private val store: MutableStateFlow<Map<String, AttachmentAnnotationEntity>>,
) : AttachmentAnnotationDao {

    override fun watchByAttachmentForUser(
        attachmentId: String,
        userId: String,
    ): Flow<List<AttachmentAnnotationEntity>> = store.map {
        it.values
            .filter { a -> a.attachmentId == attachmentId && a.userId == userId && a.deletedAt == null }
            .sortedBy { it.createdAt }
    }

    override suspend fun getByIdForUser(id: String, userId: String): AttachmentAnnotationEntity? =
        store.value[id]?.takeIf { it.userId == userId && it.deletedAt == null }

    override suspend fun listAllForUser(userId: String): List<AttachmentAnnotationEntity> =
        store.value.values.filter { it.userId == userId }

    override suspend fun upsert(entity: AttachmentAnnotationEntity) {
        store.update { it + (entity.id to entity) }
    }

    override suspend fun updateNoteForUser(id: String, note: String, ts: Long, userId: String): Int {
        val entity = store.value[id]
        if (entity == null || entity.userId != userId || entity.deletedAt != null) return 0
        store.update { it + (id to entity.copy(note = note, updatedAt = ts)) }
        return 1
    }

    override suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int {
        val entity = store.value[id]
        if (entity == null || entity.userId != userId || entity.deletedAt != null) return 0
        store.update { it + (id to entity.copy(deletedAt = ts, updatedAt = ts)) }
        return 1
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

    // The one cross-profile read in this table. Unfiltered on purpose — a fake that
    // filtered here would make the alarm re-arm look correct while the real DAO query
    // silently dropped another profile's reminders.
    override fun watchAllProfiles(): Flow<List<com.singularity.todo.core.database.TaskReminderEntity>> =
        store.map { it.values.sortedBy { r -> r.fireAt } }

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

// ─── Google Calendar sync DAOs ──────────────────────────────────────────────────

private class FakeCalendarSyncStateDao(
    private val store: MutableStateFlow<Map<Triple<String, String, String>, CalendarSyncStateEntity>>,
) : CalendarSyncStateDao {

    private fun key(userId: String, provider: String, calendarId: String) = Triple(userId, provider, calendarId)

    override suspend fun get(userId: String, provider: String, calendarId: String): CalendarSyncStateEntity? =
        store.value[key(userId, provider, calendarId)]

    override suspend fun upsert(state: CalendarSyncStateEntity) {
        store.update {
            it + (key(state.userId, state.provider, state.calendarId) to state)
        }
    }

    override suspend fun deleteForProvider(userId: String, provider: String) {
        store.update { current ->
            current.filterKeys { it.first != userId || it.second != provider }
        }
    }

    /**
     * Drops the token for one calendar only. A fake that reset every calendar would hide
     * the scoping bug this exists to make obvious.
     */
    override suspend fun invalidateToken(userId: String, provider: String, calendarId: String) {
        store.update { current ->
            current.mapValues { (k, v) ->
                if (k == key(userId, provider, calendarId)) v.copy(nextSyncToken = null) else v
            }
        }
    }
}

private class FakeGoogleEventShadowDao(
    private val store: MutableStateFlow<Map<Pair<String, String>, GoogleEventShadowEntity>>,
) : GoogleEventShadowDao {

    override suspend fun get(userId: String, eventId: String): GoogleEventShadowEntity? =
        store.value[userId to eventId]

    override suspend fun getAll(userId: String): List<GoogleEventShadowEntity> =
        store.value.values.filter { it.userId == userId }

    override fun observeMapped(userId: String): Flow<List<GoogleEventShadowEntity>> =
        store.map { values -> values.values.filter { it.userId == userId && it.taskId != null } }

    override suspend fun upsert(shadow: GoogleEventShadowEntity) {
        store.update { it + ((shadow.userId to shadow.eventId) to shadow) }
    }

    override suspend fun delete(userId: String, eventId: String) {
        store.update { it - (userId to eventId) }
    }

    override suspend fun deleteNotIn(userId: String, liveEventIds: List<String>) {
        store.update { current ->
            // Scoped to this user: another profile's rows are not ours to delete even when
            // their event ids are absent from the live set.
            current.filterValues { it.userId != userId || it.eventId in liveEventIds }
        }
    }

    override suspend fun deleteAllForUser(userId: String) {
        store.update { current -> current.filterValues { it.userId != userId } }
    }
}

private class FakeCalendarImportEventDao(
    private val store: MutableStateFlow<Map<Pair<String, String>, CalendarImportEventEntity>>,
) : CalendarImportEventDao {

    override fun observeUnconverted(userId: String): Flow<List<CalendarImportEventEntity>> =
        store.map { values ->
            values.values
                .filter { it.userId == userId && it.taskId == null }
                .sortedBy { it.startsAt }
        }

    override suspend fun getAll(userId: String): List<CalendarImportEventEntity> =
        store.value.values.filter { it.userId == userId }

    override suspend fun get(userId: String, eventId: String): CalendarImportEventEntity? =
        store.value[userId to eventId]

    override suspend fun upsert(event: CalendarImportEventEntity) {
        store.update { it + ((event.userId to event.eventId) to event) }
    }

    override suspend fun delete(userId: String, eventId: String) {
        store.update { it - (userId to eventId) }
    }

    override suspend fun linkTask(userId: String, eventId: String, taskId: String) {
        store.update { current ->
            val k = userId to eventId
            current[k]?.let { current + (k to it.copy(taskId = taskId)) } ?: current
        }
    }

    override suspend fun deleteOlderThan(userId: String, beforeMs: Long) {
        store.update { current ->
            current.filterValues { it.userId != userId || (it.startsAt ?: Long.MAX_VALUE) >= beforeMs }
        }
    }

    override suspend fun deleteAllForUser(userId: String) {
        store.update { current -> current.filterValues { it.userId != userId } }
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

    override suspend fun toggleItem(
        itemId: String,
        isCompleted: Boolean,
        updatedAt: Long,
        checkedAt: Long,
        actor: String,
        userId: String,
    ): Int {
        val existing = store.value[itemId] ?: return 0
        store.update {
            it + (
                itemId to existing.copy(
                    isCompleted = isCompleted,
                    updatedAt = updatedAt,
                    checkedBy = actor,
                    checkedAt = checkedAt,
                    rowVersion = existing.rowVersion + 1,
                )
            )
        }
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
            .groupBy { Instant.fromEpochMilliseconds(it.createdAt).toString().take(10) }
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

    override suspend fun listOwnedBy(userId: String): List<ProfileEntity> =
        store.value.values.filter { it.userId == userId }.sortedBy { it.createdAt }

    override suspend fun claimUnowned(userId: String): Int {
        var claimed = 0
        store.update { current ->
            current.mapValues { (id, profile) ->
                if (profile.userId == null) {
                    claimed++
                    profile.copy(userId = userId)
                } else {
                    profile
                }
            }
        }
        return claimed
    }
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

    override suspend fun listAllForUser(userId: String): List<AgendaViewEntity> =
        store.value.values.filter { it.userId == userId }
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

    override suspend fun getByProject(projectId: String, userId: String): List<String> =
        store.value.filter { it.projectId == projectId }.map { it.tagGroupId }

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
    override fun watchForTask(taskId: String): Flow<List<TimeEntryEntity>> = store.map { map ->
        map.values.filter { it.taskId == taskId && it.deletedAt == null }.sortedByDescending { it.startedAt }
    }

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

/**
 * In-memory [ProposalDao].
 *
 * The compare-and-set in [claimItem] is reproduced faithfully — it is the property
 * under test in `ApplyProposalItemUseCaseTest` (a second tap must apply nothing), so
 * a fake that always returned 1 would make that test lie.
 */
private class FakeProposalDao(private val proposals: MutableStateFlow<Map<String, AiProposalEntity>>) : ProposalDao {

    override suspend fun upsertProposal(entity: AiProposalEntity) {
        proposals.update { it + (entity.id to entity) }
    }

    override suspend fun getProposal(id: String): AiProposalEntity? = proposals.value[id]

    override fun watchProposal(id: String): Flow<AiProposalEntity?> = proposals.map { it[id] }

    override fun watchProposalsForTarget(
        targetId: String,
        targetKind: String,
        userId: String,
    ): Flow<List<AiProposalEntity>> = proposals.map { map ->
        map.values
            .filter {
                it.targetId == targetId &&
                    it.targetKind == targetKind &&
                    it.userId == userId
            }
            .sortedByDescending { it.createdAt }
    }

    override fun watchProposalsByTargetKind(
        userId: String,
        targetKind: String,
        status: String,
    ): Flow<List<AiProposalEntity>> = proposals.map { map ->
        map.values
            .filter {
                it.userId == userId &&
                    it.targetKind == targetKind &&
                    it.status == status
            }
            .sortedByDescending { it.createdAt }
    }

    override fun watchProposalsByStatus(userId: String, status: String): Flow<List<AiProposalEntity>> =
        proposals.map { map ->
            map.values.filter { it.userId == userId && it.status == status }
                .sortedByDescending { it.createdAt }
        }

    override suspend fun updateProposalStatus(id: String, status: String, updatedAt: Long, userId: String): Int {
        val before = proposals.value[id] ?: return 0
        if (before.userId != userId) return 0
        proposals.update { it + (id to before.copy(status = status, updatedAt = updatedAt)) }
        return 1
    }
}

/**
 * In-memory [ProposalItemDao].
 *
 * The compare-and-set in [claimItem] is reproduced faithfully — it is the property
 * under test in `ApplyProposalItemUseCaseTest` (a second tap must apply nothing), so
 * a fake that always returned 1 would make that test lie. The ownership check is
 * likewise real: a fake that skipped it would hide a cross-user decision.
 */
private class FakeProposalItemDao(
    private val proposals: MutableStateFlow<Map<String, AiProposalEntity>>,
    private val items: MutableStateFlow<Map<String, ProposalItemEntity>>,
) : ProposalItemDao {
    override suspend fun upsertItem(entity: ProposalItemEntity) {
        items.update { it + (entity.id to entity) }
    }

    override suspend fun getItem(id: String): ProposalItemEntity? = items.value[id]

    override fun watchItemsForProposal(proposalId: String): Flow<List<ProposalItemEntity>> =
        items.map { map -> map.values.filter { it.proposalId == proposalId }.sortedBy { it.sortOrder } }

    override suspend fun getItemsForProposal(proposalId: String): List<ProposalItemEntity> =
        items.value.values.filter { it.proposalId == proposalId }.sortedBy { it.sortOrder }

    override suspend fun countItemsWithFingerprint(proposalId: String, fingerprint: String): Int =
        items.value.values.count { it.proposalId == proposalId && it.fingerprint == fingerprint }

    override suspend fun claimItem(
        userId: String,
        id: String,
        status: String,
        decidedAt: Long,
        actor: String,
        reason: String?,
    ): Int {
        val before = items.value[id] ?: return 0
        // The whole point: only a row still in Pending can transition.
        if (before.status != "Pending") return 0
        if (proposals.value[before.proposalId]?.userId != userId) return 0
        items.update { map ->
            map + (
                id to before.copy(
                    status = status,
                    decidedAt = decidedAt,
                    decidedActor = actor,
                    rejectionReason = reason,
                )
            )
        }
        return 1
    }

    override suspend fun retractPendingItems(proposalId: String, userId: String): Int {
        val owned = proposals.value[proposalId]?.userId == userId
        val affected = if (owned) {
            items.value.values.filter { it.proposalId == proposalId && it.status == "Pending" }
        } else {
            emptyList()
        }
        if (affected.isEmpty()) return 0
        items.update { map ->
            map.mapValues { (key, value) ->
                if (affected.any { it.id == key }) value.copy(status = "Retracted") else value
            }
        }
        return affected.size
    }

    override suspend fun rejectedFingerprints(userId: String, limit: Int): List<String> {
        val ownerIds = proposals.value.values.filter { it.userId == userId }.map { it.id }.toSet()
        return items.value.values
            .filter { it.proposalId in ownerIds && it.status == "Rejected" }
            .sortedByDescending { it.decidedAt ?: 0L }
            .take(limit)
            .map { it.fingerprint }
    }
}

// ─── OwnerScopeDao ─────────────────────────────────────────────────────────────

/**
 * In-memory [OwnerScopeDao] — the parent id sets REQ-UA-017 scopes its erase by.
 *
 * Split from [FakeOwnerEraseDao] because it is a split in the real contract too: these
 * are reads, those are writes, and the whole reason the caller holds these ids before
 * it deletes anything is that a delete cannot find the children that named a parent
 * once that parent is gone.
 */
private class FakeOwnerScopeDao(
    private val tasks: MutableStateFlow<Map<String, TaskEntity>>,
    private val projects: MutableStateFlow<Map<String, ProjectEntity>>,
) : OwnerScopeDao {

    override suspend fun listTaskIdsForOwner(scopedUserIds: List<String>): List<String> =
        tasks.value.values.filter { it.userId in scopedUserIds }.map { it.id }

    override suspend fun listProjectIdsForOwner(scopedUserIds: List<String>): List<String> =
        projects.value.values.filter { it.userId in scopedUserIds }.map { it.id }
}

// ─── OwnerEraseDao ─────────────────────────────────────────────────────────────

/**
 * In-memory [OwnerEraseDao] — every owner-scoped delete REQ-UA-017 performs.
 *
 * It lives in one fake class for the same reason it lives in one real DAO: the four
 * child tables carry no `user_id`, so their deletes are only correct when the parent
 * id sets — read from [FakeOwnerScopeDao] first — still resolve against live parents,
 * and the caller is the thing that decides that order.
 *
 * Every sub-select is read from the **live** parent store at call time, never from a
 * captured list — `OwnerScopedEraser` passes the ids it read moments earlier, and a
 * fake that trusted that list would keep passing after the real query stopped
 * selecting through `tasks`/`projects`.
 */
private class FakeOwnerEraseDao(
    private val tasks: MutableStateFlow<Map<String, TaskEntity>>,
    private val taskTags: MutableStateFlow<List<TaskTagCrossRef>>,
    private val taskDependencies: MutableStateFlow<List<TaskDependencyCrossRef>>,
    private val checklist: MutableStateFlow<Map<String, ChecklistItemEntity>>,
    private val projects: MutableStateFlow<Map<String, ProjectEntity>>,
    private val projectTagGroups: MutableStateFlow<List<ProjectInheritedTagGroupCrossRef>>,
    private val notes: MutableStateFlow<Map<String, NoteEntity>>,
    private val tags: MutableStateFlow<Map<String, TagEntity>>,
    private val tagGroups: MutableStateFlow<Map<String, TagGroupEntity>>,
    private val taskReminders: MutableStateFlow<Map<Pair<String, String>, TaskReminderEntity>>,
    private val projectReminders: MutableStateFlow<Map<Pair<String, String>, ProjectReminderEntity>>,
) : OwnerEraseDao {

    // ── Children: scoped through their parent, at call time ───────────────────

    /**
     * The `{ id IN taskIds AND user_id IN scopedUserIds }` sub-select the three
     * task-scoped child deletes share, resolved against the store as it is now.
     */
    private fun parentTaskIds(taskIds: List<String>, scopedUserIds: List<String>): Set<String> =
        tasks.value.values
            .filter { it.id in taskIds && it.userId in scopedUserIds }
            .mapTo(mutableSetOf()) { it.id }

    override suspend fun deleteTagRefs(taskIds: List<String>, scopedUserIds: List<String>): Int {
        val parents = parentTaskIds(taskIds, scopedUserIds)
        val doomed = taskTags.value.filter { it.taskId in parents }
        taskTags.update { refs -> refs.filterNot { it.taskId in parents } }
        return doomed.size
    }

    /** Both directions: a surviving task must not keep pointing at a deleted one. */
    override suspend fun deleteDependencies(taskIds: List<String>, scopedUserIds: List<String>): Int {
        val parents = parentTaskIds(taskIds, scopedUserIds)
        val doomed = taskDependencies.value.filter { it.taskId in parents || it.dependsOnTaskId in parents }
        taskDependencies.update { refs ->
            refs.filterNot { it.taskId in parents || it.dependsOnTaskId in parents }
        }
        return doomed.size
    }

    override suspend fun deleteChecklistItems(taskIds: List<String>, scopedUserIds: List<String>): Int {
        val parents = parentTaskIds(taskIds, scopedUserIds)
        val doomed = checklist.value.values.filter { it.taskId in parents }
        checklist.update { current -> current.filterValues { it.taskId !in parents } }
        return doomed.size
    }

    /** Scoped through `projects`, not `tasks` — the same parent the real query names. */
    override suspend fun deleteInheritedTagGroups(projectIds: List<String>, scopedUserIds: List<String>): Int {
        val parents = projects.value.values
            .filter { it.id in projectIds && it.userId in scopedUserIds }
            .mapTo(mutableSetOf()) { it.id }
        val doomed = projectTagGroups.value.filter { it.projectId in parents }
        projectTagGroups.update { list -> list.filterNot { it.projectId in parents } }
        return doomed.size
    }

    // ── Parents: straight membership on `user_id` ─────────────────────────────

    override suspend fun deleteTasks(scopedUserIds: List<String>): Int {
        val doomed = tasks.value.values.filter { it.userId in scopedUserIds }.map { it.id }
        tasks.update { current -> current - doomed.toSet() }
        return doomed.size
    }

    override suspend fun deleteNotes(scopedUserIds: List<String>): Int {
        val doomed = notes.value.values.filter { it.userId in scopedUserIds }.map { it.id }
        notes.update { current -> current - doomed.toSet() }
        return doomed.size
    }

    override suspend fun deleteProjects(scopedUserIds: List<String>): Int {
        val doomed = projects.value.values.filter { it.userId in scopedUserIds }.map { it.id }
        projects.update { current -> current - doomed.toSet() }
        return doomed.size
    }

    override suspend fun deleteTags(scopedUserIds: List<String>): Int {
        val doomed = tags.value.values.filter { it.userId in scopedUserIds }.map { it.id }
        tags.update { current -> current - doomed.toSet() }
        return doomed.size
    }

    override suspend fun deleteTagGroups(scopedUserIds: List<String>): Int {
        val doomed = tagGroups.value.values.filter { it.userId in scopedUserIds }.map { it.id }
        tagGroups.update { current -> current - doomed.toSet() }
        return doomed.size
    }

    override suspend fun deleteTaskReminders(scopedUserIds: List<String>): Int {
        val doomed = taskReminders.value.values.filter { it.userId in scopedUserIds }
        taskReminders.update { current -> current.filterValues { it.userId !in scopedUserIds } }
        return doomed.size
    }

    override suspend fun deleteProjectReminders(scopedUserIds: List<String>): Int {
        val doomed = projectReminders.value.values.filter { it.userId in scopedUserIds }
        projectReminders.update { current -> current.filterValues { it.userId !in scopedUserIds } }
        return doomed.size
    }
}
