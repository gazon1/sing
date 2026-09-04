package com.singularity.todo.test.fakes

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagEntity
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
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
    private val _notes = MutableStateFlow<Map<String, NoteEntity>>(emptyMap())
    private val _projects = MutableStateFlow<Map<String, ProjectEntity>>(emptyMap())
    private val _tags = MutableStateFlow<Map<String, TagEntity>>(emptyMap())
    private val _outbox = MutableStateFlow<Map<String, SyncOutboxEntity>>(emptyMap())
    private val _attachments = MutableStateFlow<Map<String, AttachmentEntity>>(emptyMap())
    private val _reminders = MutableStateFlow<Map<Pair<String, String>, com.singularity.todo.core.database.TaskReminderEntity>>(emptyMap())

    override fun taskDao(): TaskDao = FakeTaskDao(_tasks, _taskTags)
    override fun noteDao(): NoteDao = FakeNoteDao(_notes)
    override fun projectDao(): ProjectDao = FakeProjectDao(_projects)
    override fun tagDao(): TagDao = FakeTagDao(_tags)
    override fun syncOutboxDao(): SyncOutboxDao = FakeSyncOutboxDao(_outbox)
    override fun attachmentDao(): AttachmentDao = FakeAttachmentDao(_attachments)
    override fun reminderDao(): ReminderDao = FakeReminderDao(_reminders)

    override suspend fun clearAllTables() {
        _tasks.value = emptyMap()
        _taskTags.value = emptyList()
        _notes.value = emptyMap()
        _projects.value = emptyMap()
        _tags.value = emptyMap()
        _outbox.value = emptyMap()
        _attachments.value = emptyMap()
        _reminders.value = emptyMap()
    }

    // ─── Seed helpers ────────────────────────────────────────────────────────
    // Each is a single mutation, no fixture DSL — easier to read in test setup.

    fun seedTasks(items: List<TaskEntity>) { _tasks.value = items.associateBy { it.id } }
    fun seedNotes(items: List<NoteEntity>) { _notes.value = items.associateBy { it.id } }
    fun seedProjects(items: List<ProjectEntity>) { _projects.value = items.associateBy { it.id } }
    fun seedTags(items: List<TagEntity>) { _tags.value = items.associateBy { it.id } }
    fun seedOutbox(items: List<SyncOutboxEntity>) { _outbox.value = items.associateBy { it.patchId } }
    fun seedAttachments(items: List<AttachmentEntity>) { _attachments.value = items.associateBy { it.id } }
    fun seedReminders(items: List<com.singularity.todo.core.database.TaskReminderEntity>) {
        _reminders.value = items.associateBy { it.userId to it.id }
    }
}

// ─── TaskDao ─────────────────────────────────────────────────────────────────

private class FakeTaskDao(
    private val store: MutableStateFlow<Map<String, TaskEntity>>,
    private val crossRefs: MutableStateFlow<List<TaskTagCrossRef>>,
) : TaskDao {

    override fun watchActive(userId: String): Flow<List<TaskEntity>> =
        store.map { it.values.filter { t -> t.userId == userId && t.archivedAt == null }
            .sortedWith(compareBy({ it.dueDate ?: "\uFFFF" }, { !it.isPinned })) }

    override fun watchById(id: String): Flow<TaskEntity?> = store.map { it[id] }

    override fun watchTrash(userId: String): Flow<List<TaskEntity>> =
        store.map { it.values.filter { t -> t.userId == userId && t.archivedAt != null }
            .sortedByDescending { it.archivedAt } }

    override fun watchSomeday(userId: String): Flow<List<TaskEntity>> =
        store.map { it.values.filter { t -> t.userId == userId && t.someday && t.archivedAt == null }
            .sortedByDescending { it.createdAt } }

    override fun watchByDate(userId: String, date: String): Flow<List<TaskEntity>> =
        store.map { it.values.filter { t -> t.userId == userId && t.archivedAt == null && t.dueDate == date }
            .sortedWith(compareBy({ !it.isPinned }, { it.dueTime ?: "" })) }

    override fun watchUpcoming(userId: String, today: String, endDate: String): Flow<List<TaskEntity>> =
        store.map { it.values.filter { t ->
            val due = t.dueDate ?: return@filter false
            t.userId == userId && t.archivedAt == null && due > today && due <= endDate
        }.sortedBy { it.dueDate } }

    override fun watchByProject(userId: String, projectId: String): Flow<List<TaskEntity>> =
        store.map { it.values.filter { t -> t.userId == userId && t.projectId == projectId && t.archivedAt == null }
            .sortedWith(compareBy({ !it.isPinned }, { it.dueDate ?: "" })) }

    override fun search(q: String): Flow<List<TaskEntity>> =
        store.map { it.values.filter { t ->
            t.title.contains(q, ignoreCase = true) ||
                (t.description?.contains(q, ignoreCase = true) == true)
        } }

    override suspend fun upsert(task: TaskEntity) { store.update { it + (task.id to task) } }
    override suspend fun softDelete(id: String, ts: Long) = mutateTask(id) { it.copy(archivedAt = ts, updatedAt = ts) }
    override suspend fun restore(id: String, ts: Long) = mutateTask(id) { it.copy(archivedAt = null, updatedAt = ts) }
    override suspend fun markComplete(id: String, ts: Long) = mutateTask(id) { it.copy(completedAt = ts, updatedAt = ts) }
    override suspend fun markIncomplete(id: String, ts: Long) = mutateTask(id) { it.copy(completedAt = null, updatedAt = ts) }

    override suspend fun upsertTagCrossRef(ref: TaskTagCrossRef) {
        crossRefs.update { it + ref }
    }
    override suspend fun removeTagRef(taskId: String, tagId: String) {
        crossRefs.update { it.filterNot { r -> r.taskId == taskId && r.tagId == tagId } }
    }

    override fun getTagIdsForTask(taskId: String): Flow<List<String>> =
        crossRefs.map { refs -> refs.filter { it.taskId == taskId }.map { it.tagId } }

    override suspend fun listAllForUser(userId: String): List<TaskEntity> =
        store.value.values.filter { it.userId == userId }

    private suspend fun mutateTask(id: String, fn: (TaskEntity) -> TaskEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── NoteDao ──────────────────────────────────────────────────────────────────

private class FakeNoteDao(
    private val store: MutableStateFlow<Map<String, NoteEntity>>,
) : NoteDao {

    override fun watchAll(userId: String): Flow<List<NoteEntity>> =
        store.map { it.values.filter { n -> n.userId == userId && n.deletedAt == null }
            .sortedByDescending { it.updatedAt } }

    override fun watchById(id: String): Flow<NoteEntity?> = store.map { it[id] }
    override suspend fun getById(id: String): NoteEntity? = store.value[id]

    override fun watchChildren(parentId: String): Flow<List<NoteEntity>> =
        store.map { it.values.filter { n -> n.parentNoteId == parentId && n.deletedAt == null }
            .sortedBy { it.title } }

    override fun search(q: String): Flow<List<NoteEntity>> =
        store.map { it.values.filter { n ->
            n.title.contains(q, ignoreCase = true) ||
                (n.bodyMarkdown?.contains(q, ignoreCase = true) == true)
        } }

    override suspend fun upsert(note: NoteEntity) { store.update { it + (note.id to note) } }
    override suspend fun softDelete(id: String, ts: Long) = mutate(id) { it.copy(deletedAt = ts, updatedAt = ts) }
    override suspend fun restore(id: String, ts: Long) = mutate(id) { it.copy(deletedAt = null, updatedAt = ts) }
    override suspend fun listAllForUser(userId: String): List<NoteEntity> =
        store.value.values.filter { it.userId == userId }

    private suspend fun mutate(id: String, fn: (NoteEntity) -> NoteEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── ProjectDao ────────────────────────────────────────────────────────────────

private class FakeProjectDao(
    private val store: MutableStateFlow<Map<String, ProjectEntity>>,
) : ProjectDao {

    override fun watchAll(userId: String): Flow<List<ProjectEntity>> =
        store.map { it.values.filter { p -> p.userId == userId && !p.isDeleted }
            .sortedWith(compareBy({ it.sortOrder }, { it.name })) }

    override fun watchById(id: String): Flow<ProjectEntity?> = store.map { it[id] }
    override suspend fun upsert(project: ProjectEntity) { store.update { it + (project.id to project) } }
    override suspend fun softDelete(id: String, ts: Long) = mutate(id) { it.copy(isDeleted = true, deletedAt = ts, updatedAt = ts) }
    override suspend fun listAllForUser(userId: String): List<ProjectEntity> =
        store.value.values.filter { it.userId == userId }

    private suspend fun mutate(id: String, fn: (ProjectEntity) -> ProjectEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── TagDao ────────────────────────────────────────────────────────────────────

private class FakeTagDao(
    private val store: MutableStateFlow<Map<String, TagEntity>>,
) : TagDao {

    override fun watchAll(userId: String): Flow<List<TagEntity>> =
        store.map { it.values.filter { t -> t.userId == userId && t.deletedAt == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.name })) }

    override fun watchById(id: String): Flow<TagEntity?> = store.map { it[id] }
    override suspend fun upsert(tag: TagEntity) { store.update { it + (tag.id to tag) } }
    override suspend fun softDelete(id: String, ts: Long) = mutate(id) { it.copy(deletedAt = ts, updatedAt = ts) }
    override suspend fun listAllForUser(userId: String): List<TagEntity> =
        store.value.values.filter { it.userId == userId }

    private suspend fun mutate(id: String, fn: (TagEntity) -> TagEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── SyncOutboxDao ────────────────────────────────────────────────────────────

private class FakeSyncOutboxDao(
    private val store: MutableStateFlow<Map<String, SyncOutboxEntity>>,
) : SyncOutboxDao {

    override fun watchPending(): Flow<List<SyncOutboxEntity>> =
        store.map { it.values.sortedBy { it.createdAt } }

    override suspend fun getPending(): List<SyncOutboxEntity> =
        store.value.values.sortedBy { it.createdAt }

    override suspend fun insert(entity: SyncOutboxEntity) { store.update { it + (entity.patchId to entity) } }
    override suspend fun delete(id: String) { store.update { it - id } }
    override suspend fun markFailed(id: String, error: String) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to existing.copy(attempts = existing.attempts + 1, lastError = error))
        }
    }
    override suspend fun deleteByEntity(entityId: String) {
        store.update { it.filterValues { e -> e.entityId != entityId } }
    }
    override suspend fun clearAll() { store.value = emptyMap() }
}

// ─── AttachmentDao ────────────────────────────────────────────────────────────

private class FakeAttachmentDao(
    private val store: MutableStateFlow<Map<String, AttachmentEntity>>,
) : AttachmentDao {

    override fun watchByTask(taskId: String): Flow<List<AttachmentEntity>> =
        store.map { it.values.filter { a -> a.taskId == taskId && a.deletedAt == null }
            .sortedByDescending { it.createdAt } }

    override fun watchById(id: String): Flow<AttachmentEntity?> = store.map { it[id] }

    override suspend fun upsert(entity: AttachmentEntity) { store.update { it + (entity.id to entity) } }
    override suspend fun softDelete(id: String, ts: Long) = mutate(id) { it.copy(deletedAt = ts, updatedAt = ts) }
    override suspend fun delete(id: String) { store.update { it - id } }

    override fun watchBySyncStatus(status: String): Flow<List<AttachmentEntity>> =
        store.map { it.values.filter { a -> a.syncStatus == status && a.deletedAt == null } }

    override suspend fun listAllForUser(userId: String): List<AttachmentEntity> =
        store.value.values.filter { it.userId == userId }

    private suspend fun mutate(id: String, fn: (AttachmentEntity) -> AttachmentEntity) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to fn(existing))
        }
    }
}

// ─── ReminderDao ──────────────────────────────────────────────────────────────

private class FakeReminderDao(
    private val store: MutableStateFlow<Map<Pair<String, String>, com.singularity.todo.core.database.TaskReminderEntity>>,
) : ReminderDao {

    override fun watchAll(userId: String): Flow<List<com.singularity.todo.core.database.TaskReminderEntity>> =
        store.map { it.values.filter { r -> r.userId == userId }.sortedBy { it.fireAt } }

    override fun watchByTask(taskId: String, userId: String): Flow<List<com.singularity.todo.core.database.TaskReminderEntity>> =
        store.map { it.values.filter { r -> r.taskId == taskId && r.userId == userId }.sortedBy { it.fireAt } }

    override fun watchDueBefore(now: Long, userId: String): Flow<List<com.singularity.todo.core.database.TaskReminderEntity>> =
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
}