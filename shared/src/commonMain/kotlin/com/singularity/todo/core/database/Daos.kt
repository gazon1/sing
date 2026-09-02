package com.singularity.todo.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
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

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%'")
    fun search(q: String): Flow<List<TaskEntity>>

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
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE user_id = :userId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun watchAll(userId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun watchById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE parent_note_id = :parentId AND deleted_at IS NULL ORDER BY title ASC")
    fun watchChildren(parentId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :q || '%' OR body_markdown LIKE '%' || :q || '%'")
    fun search(q: String): Flow<List<NoteEntity>>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("UPDATE notes SET deleted_at = :ts, updated_at = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    @Query("UPDATE notes SET deleted_at = NULL, updated_at = :ts WHERE id = :id")
    suspend fun restore(id: String, ts: Long)
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
}
