package com.singularity.todo.core.database

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.sync.SyncOutboxDao
import com.singularity.todo.core.sync.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM desktop database using SQLite via JDBC.
 * Does NOT extend RoomDatabase — desktop uses this directly while Android uses Room's generated AppDatabase_Impl.
 */
class JvmDatabase private constructor(private val dbPath: String) {

    private var _connection: Connection? = null

    init {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            createTables(conn)
        }
    }

    private fun createTables(conn: Connection) {
        conn.createStatement().use { s ->
            s.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, title TEXT NOT NULL,
                    description TEXT, due_date TEXT, due_time TEXT, is_pinned INTEGER DEFAULT 0,
                    completed_at INTEGER, archived_at INTEGER, someday INTEGER DEFAULT 0,
                    project_id TEXT, server_version INTEGER DEFAULT 0, sync_status TEXT DEFAULT 'LOCAL_ONLY',
                    hlc TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                )
            """.trimIndent())

            s.execute("""
                CREATE TABLE IF NOT EXISTS tags (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, name TEXT NOT NULL,
                    sort_order INTEGER DEFAULT 0, deleted_at INTEGER,
                    server_version INTEGER DEFAULT 0, sync_status TEXT DEFAULT 'LOCAL_ONLY',
                    hlc TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                )
            """.trimIndent())

            s.execute("""
                CREATE TABLE IF NOT EXISTS task_tags (
                    task_id TEXT NOT NULL, tag_id TEXT NOT NULL, PRIMARY KEY (task_id, tag_id)
                )
            """.trimIndent())

            s.execute("""
                CREATE TABLE IF NOT EXISTS notes (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, parent_note_id TEXT,
                    title TEXT NOT NULL, body_markdown TEXT, is_pinned INTEGER DEFAULT 0,
                    deleted_at INTEGER, server_version INTEGER DEFAULT 0,
                    sync_status TEXT DEFAULT 'LOCAL_ONLY', hlc TEXT,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                )
            """.trimIndent())

            s.execute("""
                CREATE TABLE IF NOT EXISTS projects (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, name TEXT NOT NULL,
                    color TEXT, icon TEXT, sort_order INTEGER DEFAULT 0,
                    is_deleted INTEGER DEFAULT 0, deleted_at INTEGER,
                    server_version INTEGER DEFAULT 0, sync_status TEXT DEFAULT 'LOCAL_ONLY',
                    hlc TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                )
            """.trimIndent())

            s.execute("""
                CREATE TABLE IF NOT EXISTS sync_outbox (
                    patch_id TEXT PRIMARY KEY, entity_id TEXT NOT NULL,
                    entity_type TEXT NOT NULL, payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL, attempts INTEGER DEFAULT 0, last_error TEXT
                )
            """.trimIndent())

            s.execute("""
                CREATE TABLE IF NOT EXISTS attachments (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL, task_id TEXT,
                    filename TEXT NOT NULL, mime_type TEXT, size_bytes INTEGER DEFAULT 0,
                    local_path TEXT, remote_url TEXT, sync_status TEXT DEFAULT 'LOCAL_ONLY',
                    deleted_at INTEGER, server_version INTEGER DEFAULT 0,
                    hlc TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    private val conn: Connection
        get() = _connection ?: DriverManager.getConnection("jdbc:sqlite:$dbPath").also { _connection = it }

    // In-memory state flows
    private val _taskFlow = MutableStateFlow<List<TaskEntity>>(emptyList())
    private val _noteFlow = MutableStateFlow<List<NoteEntity>>(emptyList())
    private val _projectFlow = MutableStateFlow<List<ProjectEntity>>(emptyList())
    private val _tagFlow = MutableStateFlow<List<TagEntity>>(emptyList())
    private val _attachmentFlow = MutableStateFlow<List<AttachmentEntity>>(emptyList())
    private val _syncOutboxFlow = MutableStateFlow<List<SyncOutboxEntity>>(emptyList())

    fun taskDao(): TaskDao = JvmTaskDao(conn, _taskFlow)
    fun noteDao(): NoteDao = JvmNoteDao(conn, _noteFlow)
    fun projectDao(): ProjectDao = JvmProjectDao(conn, _projectFlow)
    fun tagDao(): TagDao = JvmTagDao(conn, _tagFlow)
    fun syncOutboxDao(): SyncOutboxDao = JvmSyncOutboxDao(conn, _syncOutboxFlow)
    fun attachmentDao(): AttachmentDao = JvmAttachmentDao(conn, _attachmentFlow)

    fun close() {
        _connection?.close()
        _connection = null
    }

    companion object {
        fun create(dbPath: String): JvmDatabase {
            java.io.File(dbPath).parentFile?.mkdirs()
            return JvmDatabase(dbPath)
        }
    }
}

private class JvmTaskDao(
    private val conn: Connection,
    private val taskFlow: MutableStateFlow<List<TaskEntity>>
) : TaskDao {
    override fun watchActive(userId: String): Flow<List<TaskEntity>> = taskFlow
    override fun watchById(id: String): Flow<TaskEntity?> = taskFlow.map { list -> list.find { it.id == id } }
    override fun watchTrash(userId: String): Flow<List<TaskEntity>> = taskFlow.map { list -> list.filter { it.archivedAt != null && it.userId == userId } }
    override fun watchSomeday(userId: String): Flow<List<TaskEntity>> = taskFlow.map { list -> list.filter { it.someday && it.userId == userId && it.archivedAt == null } }
    override fun watchByDate(userId: String, date: String): Flow<List<TaskEntity>> = taskFlow.map { list -> list.filter { it.dueDate == date && it.userId == userId && it.archivedAt == null } }
    override fun watchUpcoming(userId: String, today: String, endDate: String): Flow<List<TaskEntity>> = taskFlow.map { list -> list.filter { it.dueDate != null && it.dueDate!! > today && it.dueDate!! <= endDate && it.userId == userId && it.archivedAt == null } }
    override fun watchByProject(userId: String, projectId: String): Flow<List<TaskEntity>> = taskFlow.map { list -> list.filter { it.projectId == projectId && it.userId == userId && it.archivedAt == null } }
    override fun search(q: String): Flow<List<TaskEntity>> = taskFlow.map { list -> list.filter { it.title.contains(q, ignoreCase = true) || (it.description?.contains(q, ignoreCase = true) == true) } }
    override suspend fun upsert(task: TaskEntity) { }
    override suspend fun softDelete(id: String, ts: Long) { }
    override suspend fun restore(id: String, ts: Long) { }
    override suspend fun markComplete(id: String, ts: Long) { }
    override suspend fun markIncomplete(id: String, ts: Long) { }
    override suspend fun upsertTagCrossRef(ref: TaskTagCrossRef) { }
    override suspend fun removeTagRef(taskId: String, tagId: String) { }
    override fun getTagIdsForTask(taskId: String): Flow<List<String>> = taskFlow.map { emptyList() }
    override suspend fun listAllForUser(userId: String): List<TaskEntity> = emptyList()
}

private class JvmNoteDao(private val conn: Connection, private val noteFlow: MutableStateFlow<List<NoteEntity>>) : NoteDao {
    override fun watchAll(userId: String): Flow<List<NoteEntity>> = noteFlow
    override fun watchById(id: String): Flow<NoteEntity?> = noteFlow.map { list -> list.find { it.id == id } }
    override fun watchChildren(parentId: String): Flow<List<NoteEntity>> = noteFlow.map { list -> list.filter { it.parentNoteId == parentId } }
    override fun search(q: String): Flow<List<NoteEntity>> = noteFlow.map { list -> list.filter { it.title.contains(q, ignoreCase = true) } }
    override suspend fun upsert(note: NoteEntity) { }
    override suspend fun softDelete(id: String, ts: Long) { }
    override suspend fun restore(id: String, ts: Long) { }
    override suspend fun listAllForUser(userId: String): List<NoteEntity> = emptyList()
}

private class JvmProjectDao(private val conn: Connection, private val projectFlow: MutableStateFlow<List<ProjectEntity>>) : ProjectDao {
    override fun watchAll(userId: String): Flow<List<ProjectEntity>> = projectFlow
    override fun watchById(id: String): Flow<ProjectEntity?> = projectFlow.map { list -> list.find { it.id == id } }
    override suspend fun upsert(project: ProjectEntity) { }
    override suspend fun softDelete(id: String, ts: Long) { }
    override suspend fun listAllForUser(userId: String): List<ProjectEntity> = emptyList()
}

private class JvmTagDao(private val conn: Connection, private val tagFlow: MutableStateFlow<List<TagEntity>>) : TagDao {
    override fun watchAll(userId: String): Flow<List<TagEntity>> = tagFlow
    override fun watchById(id: String): Flow<TagEntity?> = tagFlow.map { list -> list.find { it.id == id } }
    override suspend fun upsert(tag: TagEntity) { }
    override suspend fun softDelete(id: String, ts: Long) { }
    override suspend fun listAllForUser(userId: String): List<TagEntity> = emptyList()
}

private class JvmSyncOutboxDao(private val conn: Connection, private val outboxFlow: MutableStateFlow<List<SyncOutboxEntity>>) : SyncOutboxDao {
    override fun watchPending(): Flow<List<SyncOutboxEntity>> = outboxFlow
    override suspend fun getPending(): List<SyncOutboxEntity> = emptyList()
    override suspend fun insert(entity: SyncOutboxEntity) { }
    override suspend fun delete(id: String) { }
    override suspend fun markFailed(id: String, error: String) { }
    override suspend fun deleteByEntity(entityId: String) { }
    override suspend fun clearAll() { }
}

private class JvmAttachmentDao(private val conn: Connection, private val attachmentFlow: MutableStateFlow<List<AttachmentEntity>>) : AttachmentDao {
    override fun watchByTask(taskId: String): Flow<List<AttachmentEntity>> = attachmentFlow.map { list -> list.filter { it.taskId == taskId } }
    override fun watchById(id: String): Flow<AttachmentEntity?> = attachmentFlow.map { list -> list.find { it.id == id } }
    override suspend fun upsert(entity: AttachmentEntity) { }
    override suspend fun softDelete(id: String, ts: Long) { }
    override fun watchBySyncStatus(status: String): Flow<List<AttachmentEntity>> = attachmentFlow.map { list -> list.filter { it.syncStatus == status } }
    override suspend fun delete(id: String) { }
    override suspend fun listAllForUser(userId: String): List<AttachmentEntity> = emptyList()
}
