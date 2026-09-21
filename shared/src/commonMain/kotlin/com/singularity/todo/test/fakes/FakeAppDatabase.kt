package com.singularity.todo.test.fakes

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentEntity
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
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.sync.SyncOutboxDao
import com.singularity.todo.core.sync.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val _checklist = MutableStateFlow<Map<String, ChecklistItemEntity>>(emptyMap())
    private val _llmUsage = MutableStateFlow<Map<String, LlmUsageEntity>>(emptyMap())
    private val _profiles = MutableStateFlow<Map<String, ProfileEntity>>(emptyMap())
    private val _agendaViews = MutableStateFlow<Map<String, AgendaViewEntity>>(emptyMap())

    override fun taskDao(): TaskDao = FakeTaskDao(_tasks, _taskTags, _taskDependencies)
    override fun noteDao(): NoteDao = FakeNoteDao(_notes)
    override fun projectDao(): ProjectDao = FakeProjectDao(_projects)
    override fun tagDao(): TagDao = FakeTagDao(_tags)
    override fun syncOutboxDao(): SyncOutboxDao = FakeSyncOutboxDao(_outbox)
    override fun attachmentDao(): AttachmentDao = FakeAttachmentDao(_attachments)
    override fun reminderDao(): ReminderDao = FakeReminderDao(_reminders)
    override fun checklistDao(): ChecklistDao = FakeChecklistDao(_checklist)
    override fun llmUsageDao(): LlmUsageDao = FakeLlmUsageDao(_llmUsage)
    override fun profileDao(): ProfileDao = FakeProfileDao(_profiles)
    override fun agendaViewDao(): AgendaViewDao = FakeAgendaViewDao(_agendaViews)

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
        _checklist.value = emptyMap()
        _llmUsage.value = emptyMap()
        _profiles.value = emptyMap()
        _agendaViews.value = emptyMap()
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

    override fun watchTrash(userId: String): Flow<List<TaskEntity>> = store.map {
        it.values.filter { t -> t.userId == userId && t.archivedAt != null }
            .sortedByDescending { it.archivedAt }
    }

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

    override fun watchByRegexp(userId: String, pattern: String): Flow<List<TaskEntity>> =
        store.map { tasks ->
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
                (t.title.contains(q, ignoreCase = true) ||
                    (t.description?.contains(q, ignoreCase = true) == true))
        }
    }

    override suspend fun upsert(task: TaskEntity) {
        store.update { it + (task.id to task) }
    }
    override suspend fun softDelete(id: String, ts: Long) = mutateTask(id) { it.copy(archivedAt = ts, updatedAt = ts) }
    override suspend fun restore(id: String, ts: Long) = mutateTask(id) { it.copy(archivedAt = null, updatedAt = ts) }
    override suspend fun getById(id: String): TaskEntity? = store.value[id]
    override suspend fun markComplete(id: String, ts: Long) = mutateTask(
        id,
    ) { it.copy(completedAt = ts, updatedAt = ts) }
    override suspend fun archiveCompleted(ts: Long): Int {
        var count = 0
        store.update { current ->
            current.mapValues { (_, task) ->
                if (task.completedAt != null && task.archivedAt == null) {
                    count++
                    task.copy(archivedAt = ts, updatedAt = ts)
                } else {
                    task
                }
            }
        }
        return count
    }
    override suspend fun markIncomplete(id: String, ts: Long) = mutateTask(
        id,
    ) { it.copy(completedAt = null, updatedAt = ts) }
    override suspend fun setPinned(id: String, pinned: Boolean, ts: Long) = mutateTask(
        id,
    ) { it.copy(isPinned = pinned, updatedAt = ts) }

    override suspend fun upsertTagCrossRef(ref: TaskTagCrossRef) {
        crossRefs.update { it + ref }
    }
    override suspend fun removeTagRef(taskId: String, tagId: String) {
        crossRefs.update { it.filterNot { r -> r.taskId == taskId && r.tagId == tagId } }
    }

    override fun getTagIdsForTask(taskId: String): Flow<List<String>> =
        crossRefs.map { refs -> refs.filter { it.taskId == taskId }.map { it.tagId } }

    override fun getDependencyIdsForTask(taskId: String): Flow<List<String>> =
        depRefs.map { refs -> refs.filter { it.taskId == taskId }.map { it.dependsOnTaskId } }

    override fun getBlockingTaskIdsForTask(taskId: String): Flow<List<String>> =
        depRefs.map { refs -> refs.filter { it.dependsOnTaskId == taskId }.map { it.taskId } }

    override suspend fun upsertDependency(ref: TaskDependencyCrossRef) {
        depRefs.update { existing ->
            if (existing.any { it.taskId == ref.taskId && it.dependsOnTaskId == ref.dependsOnTaskId }) {
                existing
            } else {
                existing + ref
            }
        }
    }

    override suspend fun removeDependency(taskId: String, depId: String) {
        depRefs.update { it.filterNot { r -> r.taskId == taskId && r.dependsOnTaskId == depId } }
    }

    override suspend fun clearDependencies(taskId: String) {
        depRefs.update { it.filterNot { r -> r.taskId == taskId } }
    }

    override suspend fun listAllForUser(userId: String): List<TaskEntity> =
        store.value.values.filter { it.userId == userId }

    override suspend fun listAllDependenciesForUser(userId: String): List<TaskDependencyCrossRef> =
        depRefs.value.filter { ref ->
            store.value[ref.taskId]?.userId == userId
        }

    override suspend fun listAllTagsForUser(userId: String): List<TaskTagCrossRef> =
        crossRefs.value.filter { ref ->
            store.value[ref.taskId]?.userId == userId
        }

    override suspend fun searchTitles(userId: String, q: String): List<TaskEntity> =
        store.value.values.filter {
            it.userId == userId && it.archivedAt == null && it.title.contains(q, ignoreCase = true)
        }.sortedByDescending { it.updatedAt }.take(20)

    private fun mutateTask(id: String, fn: (TaskEntity) -> TaskEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── NoteDao ──────────────────────────────────────────────────────────────────

private class FakeNoteDao(private val store: MutableStateFlow<Map<String, NoteEntity>>) : NoteDao {

    override fun watchAll(userId: String): Flow<List<NoteEntity>> = store.map {
        it.values.filter { n -> n.userId == userId && n.deletedAt == null }
            .sortedWith(compareBy({ !it.isPinned }, { it.sortOrder }, { -it.updatedAt }))
    }

    override fun watchPinned(userId: String): Flow<List<NoteEntity>> = store.map {
        it.values.filter { n -> n.userId == userId && n.isPinned && n.deletedAt == null }
            .sortedByDescending { it.pinnedAt }
    }

    override fun watchArchived(userId: String): Flow<List<NoteEntity>> = store.map {
        it.values.filter { n -> n.userId == userId && n.archivedAt != null && n.deletedAt == null }
            .sortedByDescending { it.archivedAt }
    }

    override fun watchRootNotes(userId: String): Flow<List<NoteEntity>> = store.map {
        it.values.filter { n ->
            n.userId == userId && n.parentNoteId == null && !n.isFolder &&
                n.deletedAt == null
        }
            .sortedWith(compareBy({ it.sortOrder }, { -it.updatedAt }))
    }

    override fun watchByIdForUser(id: String, userId: String): Flow<NoteEntity?> = store.map { n ->
        n.values.find { it.id == id && it.userId == userId }
    }
    override suspend fun getByIdForUser(id: String, userId: String): NoteEntity? =
        store.value.values.find { it.id == id && it.userId == userId }

    override fun watchChildren(parentId: String): Flow<List<NoteEntity>> = store.map {
        it.values.filter { n -> n.parentNoteId == parentId && n.deletedAt == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.title }))
    }

    override fun watchSearchByTitle(userId: String, q: String): Flow<List<NoteEntity>> = store.map { map ->
        map.values.filter { n ->
            n.userId == userId && n.deletedAt == null && n.title.contains(q, ignoreCase = true)
        }.sortedByDescending { it.updatedAt }.take(20)
    }

    override suspend fun upsert(note: NoteEntity) {
        store.update { it + (note.id to note) }
    }
    override suspend fun softDelete(id: String, ts: Long) = mutate(id) { it.copy(deletedAt = ts, updatedAt = ts) }
    override suspend fun restore(id: String, ts: Long) = mutate(id) { it.copy(deletedAt = null, updatedAt = ts) }
    override suspend fun archive(id: String, ts: Long) = mutate(id) { it.copy(archivedAt = ts, updatedAt = ts) }
    override suspend fun unarchive(id: String, ts: Long) = mutate(id) { it.copy(archivedAt = null, updatedAt = ts) }
    override suspend fun updateContent(
        id: String,
        title: String,
        markdown: String,
        html: String,
        wordCount: Int,
        charCount: Int,
        updatedAt: Long,
    ) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (
                id to existing.copy(
                title = title,
                bodyMarkdown = markdown,
                bodyHtml = html,
                wordCount = wordCount,
                charCount = charCount,
                updatedAt = updatedAt,
            )
            )
        }
    }
    override suspend fun setPinned(id: String, pinned: Boolean, pinnedAt: Long?, ts: Long) =
        mutate(id) { it.copy(isPinned = pinned, pinnedAt = pinnedAt, updatedAt = ts) }
    override suspend fun setColor(id: String, color: Int?, ts: Long) =
        mutate(id) { it.copy(color = color, updatedAt = ts) }
    override suspend fun setSortOrder(id: String, sortOrder: Int, ts: Long) =
        mutate(id) { it.copy(sortOrder = sortOrder, updatedAt = ts) }
    override suspend fun listAllForUser(userId: String): List<NoteEntity> =
        store.value.values.filter { it.userId == userId }

    override suspend fun searchByTitle(userId: String, q: String): List<NoteEntity> = store.value.values.filter {
        it.userId == userId && it.deletedAt == null && it.title.contains(
            q,
            ignoreCase = true,
        )
    }
        .sortedByDescending { it.updatedAt }.take(20)

    override suspend fun setOutgoingLinks(id: String, linksJson: String, updatedAt: Long) =
        mutate(id) { it.copy(outgoingLinks = linksJson, updatedAt = updatedAt) }

    override suspend fun getBacklinkNotes(noteId: String, userId: String): List<NoteEntity> = store.value.values.filter { n ->
        n.userId == userId && n.deletedAt == null && n.outgoingLinks.contains("note://$noteId")
    }.take(20)

    private fun mutate(id: String, fn: (NoteEntity) -> NoteEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── ProjectDao ────────────────────────────────────────────────────────────────

private class FakeProjectDao(private val store: MutableStateFlow<Map<String, ProjectEntity>>) : ProjectDao {

    override fun watchAll(userId: String): Flow<List<ProjectEntity>> = store.map {
        it.values.filter { p -> p.userId == userId && !p.isDeleted }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
    }

    // ─── UserId-scoped reads (Phase 2.8 fix) ──────────────────────────────────
    override fun watchByIdForUser(id: String, userId: String): Flow<ProjectEntity?> = store.map { it[id]?.takeIf { p -> p.userId == userId } }
    override suspend fun getByIdForUser(id: String, userId: String): ProjectEntity? = store.value[id]?.takeIf { it.userId == userId }
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
    override suspend fun setParent(id: String, parentId: String?, ts: Long) =
        mutate(id) { it.copy(parentId = parentId, updatedAt = ts) }
    override suspend fun setSortOrder(id: String, sortOrder: Int, ts: Long) =
        mutate(id) { it.copy(sortOrder = sortOrder, updatedAt = ts) }
    override suspend fun restore(id: String, ts: Long) =
        mutate(id) { it.copy(isDeleted = false, deletedAt = null, updatedAt = ts) }
    override suspend fun findByIdempotencyKey(key: String): ProjectEntity? =
        store.value.values.firstOrNull { it.idempotencyKey == key }
    override suspend fun upsert(project: ProjectEntity) {
        store.update { it + (project.id to project) }
    }
    override suspend fun softDelete(id: String, ts: Long) = mutate(
        id,
    ) { it.copy(isDeleted = true, deletedAt = ts, updatedAt = ts) }
    override suspend fun listAllForUser(userId: String): List<ProjectEntity> =
        store.value.values.filter { it.userId == userId }

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

    override suspend fun upsert(tag: TagEntity) {
        store.update { it + (tag.id to tag) }
    }

    override suspend fun softDelete(id: String, ts: Long) = mutate(id) { it.copy(deletedAt = ts, updatedAt = ts) }

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
    override fun watchByIdForUser(id: String, userId: String): Flow<AttachmentEntity?> = store.map { it[id]?.takeIf { a -> a.userId == userId } }
    override fun watchBySyncStatusForUser(status: String, userId: String): Flow<List<AttachmentEntity>> =
        store.map { it.values.filter { a -> a.syncStatus == status && a.userId == userId && a.deletedAt == null } }

    // ─── Legacy ────────────────────────────────────────────────────────────────
    override fun watchById(id: String): Flow<AttachmentEntity?> = store.map { it[id] }

    override suspend fun upsert(entity: AttachmentEntity) {
        store.update { it + (entity.id to entity) }
    }
    override suspend fun softDelete(id: String, ts: Long) = mutate(id) { it.copy(deletedAt = ts, updatedAt = ts) }
    override suspend fun delete(id: String) {
        store.update { it - id }
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

    override fun watchDueBefore(
        now: Long,
        userId: String,
    ): Flow<List<com.singularity.todo.core.database.TaskReminderEntity>> =
        store.map { it.values.filter { r -> r.fireAt <= now && r.userId == userId }.sortedBy { it.fireAt } }

    override suspend fun upsert(reminder: com.singularity.todo.core.database.TaskReminderEntity) {
        store.update { it + ((reminder.userId to reminder.id) to reminder) }
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

    override fun watchByIdForUser(id: String, userId: String): Flow<com.singularity.todo.core.database.TaskReminderEntity?> =
        store.map { it[userId to id] }
}

// ─── ChecklistDao ───────────────────────────────────────────────────────────────

private class FakeChecklistDao(private val store: MutableStateFlow<Map<String, ChecklistItemEntity>>) : ChecklistDao {

    override fun watchByTask(taskId: String): Flow<List<ChecklistItemEntity>> =
        store.map { it.values.filter { c -> c.taskId == taskId }.sortedBy { c -> c.sortOrder } }

    override suspend fun upsert(item: ChecklistItemEntity) {
        store.update { it + (item.id to item) }
    }

    override suspend fun delete(id: String) {
        store.update { it - id }
    }

    override suspend fun deleteByTask(taskId: String) {
        store.update { current -> current.filterValues { c -> c.taskId != taskId } }
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
