package com.singularity.todo.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL ORDER BY due_date ASC, is_pinned DESC")
    fun watchActive(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun watchById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NOT NULL ORDER BY archived_at DESC")
    fun watchTrash(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND someday = 1 ORDER BY created_at DESC")
    fun watchSomeday(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND due_date = :date ORDER BY is_pinned DESC, due_time ASC")
    fun watchByDate(userId: String, date: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND due_date > :today AND due_date <= :endDate ORDER BY due_date ASC, is_pinned DESC")
    fun watchUpcoming(userId: String, today: String, endDate: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND project_id = :projectId ORDER BY is_pinned DESC, due_date ASC")
    fun watchByProject(userId: String, projectId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND archived_at IS NULL AND is_pinned = 1 ORDER BY is_pinned DESC, due_date ASC")
    fun watchPinned(userId: String): Flow<List<TaskEntity>>

    @Query("UPDATE tasks SET is_pinned = :pinned, updated_at = :ts WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, ts: Long)

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%'")
    fun search(q: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE archived_at IS NULL AND title LIKE '%' || :q || '%' ORDER BY updated_at DESC LIMIT 20")
    suspend fun searchByTitle(q: String): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET archived_at = :ts, updated_at = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    @Query("UPDATE tasks SET archived_at = NULL, updated_at = :ts WHERE id = :id")
    suspend fun restore(id: String, ts: Long)

    @Query("UPDATE tasks SET completed_at = :ts, updated_at = :ts WHERE id = :id")
    suspend fun markComplete(id: String, ts: Long)

    @Query("UPDATE tasks SET completed_at = NULL, updated_at = :ts WHERE id = :id")
    suspend fun markIncomplete(id: String, ts: Long)

    @Upsert
    suspend fun upsertTagCrossRef(ref: TaskTagCrossRef)

    @Query("DELETE FROM task_tags WHERE task_id = :taskId AND tag_id = :tagId")
    suspend fun removeTagRef(taskId: String, tagId: String)

    @Query("SELECT tag_id FROM task_tags WHERE task_id = :taskId")
    fun getTagIdsForTask(taskId: String): Flow<List<String>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<TaskEntity>

    @Query("UPDATE tasks SET archived_at = :ts, updated_at = :ts WHERE completed_at IS NOT NULL AND archived_at IS NULL")
    suspend fun archiveCompleted(ts: Long): Int
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE user_id = :userId AND deleted_at IS NULL ORDER BY is_pinned DESC, sort_order ASC, updated_at DESC")
    fun watchAll(userId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE user_id = :userId AND is_pinned = 1 AND deleted_at IS NULL ORDER BY pinned_at DESC")
    fun watchPinned(userId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE user_id = :userId AND archived_at IS NOT NULL AND deleted_at IS NULL ORDER BY archived_at DESC")
    fun watchArchived(userId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE user_id = :userId AND parent_note_id IS NULL AND is_folder = 0 AND deleted_at IS NULL ORDER BY sort_order ASC, updated_at DESC")
    fun watchRootNotes(userId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE parent_note_id = :parentId AND deleted_at IS NULL ORDER BY sort_order ASC, title ASC")
    fun watchChildren(parentId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun watchById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :q || '%' OR body_markdown LIKE '%' || :q || '%'")
    fun search(q: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE user_id = :userId AND deleted_at IS NULL AND title LIKE '%' || :q || '%' ORDER BY updated_at DESC LIMIT 20")
    suspend fun searchByTitle(userId: String, q: String): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    /** Atomic update — does NOT require a prior read. */
    @Query("UPDATE notes SET title = :title, body_markdown = :markdown, body_html = :html, word_count = :wordCount, char_count = :charCount, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateContent(id: String, title: String, markdown: String, html: String, wordCount: Int, charCount: Int, updatedAt: Long)

    @Query("UPDATE notes SET is_pinned = :pinned, pinned_at = :pinnedAt, updated_at = :ts WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, pinnedAt: Long?, ts: Long)

    @Query("UPDATE notes SET archived_at = :ts, updated_at = :ts WHERE id = :id")
    suspend fun archive(id: String, ts: Long)

    @Query("UPDATE notes SET archived_at = NULL, updated_at = :ts WHERE id = :id")
    suspend fun unarchive(id: String, ts: Long)

    @Query("UPDATE notes SET color = :color, updated_at = :ts WHERE id = :id")
    suspend fun setColor(id: String, color: Int?, ts: Long)

    @Query("UPDATE notes SET sort_order = :sortOrder, updated_at = :ts WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int, ts: Long)

    @Query("UPDATE notes SET deleted_at = :ts, updated_at = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    @Query("UPDATE notes SET deleted_at = NULL, updated_at = :ts WHERE id = :id")
    suspend fun restore(id: String, ts: Long)

    @Query("SELECT * FROM notes WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<NoteEntity>

    @Query("UPDATE notes SET outgoing_links = :linksJson, updated_at = :updatedAt WHERE id = :id")
    suspend fun setOutgoingLinks(id: String, linksJson: String, updatedAt: Long)

    /** Notes that link TO the given noteId via note:// URL scheme. */
    @Query("""
        SELECT * FROM notes
        WHERE deleted_at IS NULL
        AND outgoing_links LIKE '%note://' || :noteId || '%'
        LIMIT 20
    """)
    suspend fun getBacklinkNotes(noteId: String): List<NoteEntity>
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE user_id = :userId AND is_deleted = 0 ORDER BY sort_order ASC, name ASC")
    fun watchAll(userId: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun watchById(id: String): Flow<ProjectEntity?>

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET is_deleted = 1, deleted_at = :ts, updated_at = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    @Query("SELECT * FROM projects WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<ProjectEntity>
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE user_id = :userId AND deleted_at IS NULL ORDER BY sort_order ASC, name ASC")
    fun watchAll(userId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    fun watchById(id: String): Flow<TagEntity?>

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("UPDATE tags SET deleted_at = :ts, updated_at = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    @Query("SELECT * FROM tags WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<TagEntity>
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM task_reminders WHERE user_id = :userId ORDER BY fire_at ASC")
    fun watchAll(userId: String): Flow<List<TaskReminderEntity>>

    @Query("SELECT * FROM task_reminders WHERE task_id = :taskId AND user_id = :userId ORDER BY fire_at ASC")
    fun watchByTask(taskId: String, userId: String): Flow<List<TaskReminderEntity>>

    @Query("SELECT * FROM task_reminders WHERE fire_at <= :now AND user_id = :userId ORDER BY fire_at ASC")
    fun watchDueBefore(now: Long, userId: String): Flow<List<TaskReminderEntity>>

    @Upsert
    suspend fun upsert(reminder: TaskReminderEntity)

    @Query("DELETE FROM task_reminders WHERE id = :id AND user_id = :userId")
    suspend fun delete(id: String, userId: String)

    @Query("DELETE FROM task_reminders WHERE task_id = :taskId AND user_id = :userId")
    suspend fun deleteByTask(taskId: String, userId: String)

    @Query("SELECT * FROM task_reminders WHERE id = :id AND user_id = :userId")
    suspend fun getById(id: String, userId: String): TaskReminderEntity?
}

@Dao
interface ChecklistDao {
    @Query("SELECT * FROM checklist_items WHERE task_id = :taskId ORDER BY sort_order ASC")
    fun watchByTask(taskId: String): kotlinx.coroutines.flow.Flow<List<com.singularity.todo.core.database.ChecklistItemEntity>>

    @Upsert
    suspend fun upsert(item: com.singularity.todo.core.database.ChecklistItemEntity)

    @Query("DELETE FROM checklist_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM checklist_items WHERE task_id = :taskId")
    suspend fun deleteByTask(taskId: String)
}

@Dao
interface LlmUsageDao {
    @androidx.room3.Upsert
    suspend fun upsert(entity: LlmUsageEntity)

    @Query("SELECT * FROM llm_usage WHERE profile_id = :profileId ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(profileId: String, limit: Int): Flow<List<LlmUsageEntity>>

    @Query("""
        SELECT date(created_at / 1000, 'unixepoch') as date,
               SUM(total_tokens) as totalTokens,
               SUM(cost_usd_micros) as totalCostMicros,
               COUNT(*) as callCount
        FROM llm_usage
        WHERE profile_id = :profileId
          AND created_at >= :sinceEpochMs
        GROUP BY date(created_at / 1000, 'unixepoch')
        ORDER BY date DESC
    """)
    fun observeByDay(profileId: String, sinceEpochMs: Long): Flow<List<DailyUsageRow>>

    @Query("""
        SELECT tool_name as toolName,
               SUM(total_tokens) as totalTokens,
               SUM(cost_usd_micros) as totalCostMicros,
               COUNT(*) as callCount
        FROM llm_usage
        WHERE profile_id = :profileId
        GROUP BY tool_name
        ORDER BY SUM(total_tokens) DESC
    """)
    fun observeByTool(profileId: String): Flow<List<ToolUsageRow>>

    @Query("""
        SELECT model_id as modelId,
               SUM(total_tokens) as totalTokens,
               SUM(cost_usd_micros) as totalCostMicros,
               COUNT(*) as callCount
        FROM llm_usage
        WHERE profile_id = :profileId
        GROUP BY model_id
        ORDER BY SUM(total_tokens) DESC
    """)
    fun observeByModel(profileId: String): Flow<List<ModelUsageRow>>

    @Query("DELETE FROM llm_usage WHERE created_at < :cutoffEpochMs")
    suspend fun pruneOlderThan(cutoffEpochMs: Long)
}

/** Row type returned by observeByDay */
data class DailyUsageRow(
    val date: String,
    val totalTokens: Long,
    val totalCostMicros: Long?,
    val callCount: Long,
)

/** Row type returned by observeByTool */
data class ToolUsageRow(
    val toolName: String,
    val totalTokens: Long,
    val totalCostMicros: Long?,
    val callCount: Long,
)

/** Row type returned by observeByModel */
data class ModelUsageRow(
    val modelId: String,
    val totalTokens: Long,
    val totalCostMicros: Long?,
    val callCount: Long,
)

// ─── Profile DAO ───────────────────────────────────────────────────────────────

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY created_at ASC")
    fun all(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE is_default = 1 LIMIT 1")
    suspend fun getDefault(): ProfileEntity?

    @Query("SELECT name FROM profiles")
    suspend fun allNames(): List<String>

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int
}
