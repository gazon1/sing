package com.singularity.todo.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.singularity.todo.feature.tasks.TaskKind
import com.singularity.todo.feature.tasks.TaskPriority
import kotlinx.datetime.Instant

@Entity(
    tableName = "tasks",
    indices = [
        Index("user_id"), Index("due_date"), Index("archived_at"), Index("project_id")
    ]
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    @ColumnInfo("priority") val priority: TaskPriority = TaskPriority.None,
    @ColumnInfo("kind") val kind: TaskKind = TaskKind.Task,
    @ColumnInfo("project_id") val projectId: String?,
    @ColumnInfo("due_date") val dueDate: String?, // ISO LocalDate
    @ColumnInfo("due_time") val dueTime: String?, // "HH:mm"
    @ColumnInfo("completed_at") val completedAt: Long?, // epoch millis
    val someday: Boolean = false,
    @ColumnInfo("archived_at") val archivedAt: Long?, // epoch millis
    @ColumnInfo("is_pinned") val isPinned: Boolean = false,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("user_id") val userId: String
)

@Entity(
    tableName = "task_tags",
    primaryKeys = ["task_id", "tag_id"],
    indices = [Index("task_id"), Index("tag_id")]
)
data class TaskTagCrossRef(
    @ColumnInfo("task_id") val taskId: String,
    val tagId: String
)

@Entity(
    tableName = "notes",
    indices = [Index("user_id"), Index("deleted_at"), Index("parent_note_id")]
)
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("user_id") val userId: String,
    val title: String = "",
    @ColumnInfo("body_markdown") val bodyMarkdown: String?,
    @ColumnInfo("body_html") val bodyHtml: String?,
    @ColumnInfo("is_folder") val isFolder: Boolean = false,
    @ColumnInfo("parent_note_id") val parentNoteId: String?,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("deleted_at") val deletedAt: Long?,
    @ColumnInfo("archived_at") val archivedAt: Long?
)

@Entity(
    tableName = "projects",
    indices = [Index("user_id"), Index("deleted_at")]
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("user_id") val userId: String,
    val name: String,
    val color: Int,
    val icon: String?,
    val description: String?,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("is_default") val isDefault: Boolean = false,
    @ColumnInfo("due_date") val dueDate: String?,
    val team: String?,
    @ColumnInfo("is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo("deleted_at") val deletedAt: Long?,
    @ColumnInfo("parent_id") val parentId: String?,
    @ColumnInfo("sort_order") val sortOrder: Int = 0,
    @ColumnInfo("is_notebook") val isNotebook: Boolean = false,
    @ColumnInfo("external_id") val externalId: String?
)

@Entity(
    tableName = "tags",
    indices = [Index("user_id"), Index("deleted_at")]
)
data class TagEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("user_id") val userId: String,
    val name: String,
    val color: Int,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("parent_id") val parentId: String?,
    @ColumnInfo("sort_order") val sortOrder: Int = 0,
    @ColumnInfo("deleted_at") val deletedAt: Long?,
)
