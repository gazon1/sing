package com.singularity.todo.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority

/**
 * Mixin for sync metadata. Room flattens @Embedded columns into the parent table,
 * so the schema is identical — this only removes the duplication across 4 entities.
 */
data class SyncColumns(
    @ColumnInfo("server_version") val serverVersion: Long = 0L,
    @ColumnInfo("sync_status") val syncStatus: String = "LOCAL_ONLY",
    @ColumnInfo("sync_error") val syncError: String? = null,
    @ColumnInfo("last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo("device_id") val deviceId: String? = null,
    @ColumnInfo("hlc") val hlc: String? = null,
)

@Entity(
    tableName = "tasks",
    indices = [
        Index("user_id"), Index("due_date"), Index("archived_at"), Index("project_id"),
        Index("recurrence_rule"),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    @ColumnInfo("priority") val priority: TaskPriority = TaskPriority.None,
    @ColumnInfo("kind") val kind: TaskKind = TaskKind.Task,
    @ColumnInfo("project_id") val projectId: String?,
    @ColumnInfo("parent_task_id") val parentTaskId: String? = null,
    @ColumnInfo("due_date") val dueDate: String?, // ISO LocalDate
    @ColumnInfo("due_time") val dueTime: String?, // ISO "HH:mm:ss"; map to kotlinx LocalTime via Mappers
    @ColumnInfo("start_date") val startDate: String?, // ISO LocalDate — when the task becomes active
    @ColumnInfo("start_time") val startTime: String?, // ISO "HH:mm:ss"
    @ColumnInfo("end_date") val endDate: String?, // ISO LocalDate — deadline / end of active window
    @ColumnInfo("end_time") val endTime: String?, // ISO "HH:mm:ss"
    @ColumnInfo("accent_color") val accentColor: Long?, // ARGB color value, null = use default
    @ColumnInfo("emoji") val emoji: String?, // task-level emoji, null = none
    @ColumnInfo("completed_at") val completedAt: Long?, // epoch millis
    val someday: Boolean = false,
    @ColumnInfo("archived_at") val archivedAt: Long?, // epoch millis
    @ColumnInfo("is_pinned") val isPinned: Boolean = false,
    @ColumnInfo("recurrence_rule") val recurrenceRule: String? = null, // JSON of RecurrenceSpec
    @ColumnInfo("outgoing_links", defaultValue = "[]") val outgoingLinks: String = "[]", // wikilink backlinks
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("user_id") val userId: String,
    @Embedded val sync: SyncColumns = SyncColumns(),
)

@Entity(
    tableName = "task_tags",
    primaryKeys = ["task_id", "tag_id"],
    indices = [Index("task_id"), Index("tag_id")],
)
data class TaskTagCrossRef(
    @ColumnInfo("task_id") val taskId: String,
    @ColumnInfo("tag_id") val tagId: String,
)

/**
 * Join table for task dependencies (blocked / blocking).
 *
 * [taskId] is the dependent task — it is **blocked** until every task in
 * [dependsOnTaskId] is completed.
 *
 * This mirrors the Taskwarrior `depends:` attribute. No cycles are
 * validated in the DB layer (see [com.singularity.todo.feature.tasks.domain.port.DependencyValidator]).
 *
 * @see com.singularity.todo.docs.decisions.2026-09-18-task-dependencies
 */
@Entity(
    tableName = "task_dependencies",
    primaryKeys = ["task_id", "depends_on_task_id"],
    indices = [Index("task_id"), Index("depends_on_task_id")],
)
data class TaskDependencyCrossRef(
    @ColumnInfo("task_id") val taskId: String,
    @ColumnInfo("depends_on_task_id") val dependsOnTaskId: String,
)

@Entity(
    tableName = "notes",
    indices = [
        Index("user_id"),
        Index("deleted_at"),
        Index("parent_note_id"),
        Index("is_pinned"),
        Index("archived_at"),
        Index("sort_order"),
    ],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("user_id") val userId: String,
    val title: String = "",
    @ColumnInfo("body_markdown") val bodyMarkdown: String?,
    @ColumnInfo("body_html") val bodyHtml: String?,
    @ColumnInfo("is_folder") val isFolder: Boolean = false,
    @ColumnInfo("parent_note_id") val parentNoteId: String?,
    @ColumnInfo("is_pinned", defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo("pinned_at") val pinnedAt: Long? = null,
    @ColumnInfo("color") val color: Int? = null,
    @ColumnInfo("sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo("word_count", defaultValue = "0") val wordCount: Int = 0,
    @ColumnInfo("char_count", defaultValue = "0") val charCount: Int = 0,
    @ColumnInfo("outgoing_links", defaultValue = "[]") val outgoingLinks: String = "[]",
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("deleted_at") val deletedAt: Long?,
    @ColumnInfo("archived_at") val archivedAt: Long?,
    @Embedded val sync: SyncColumns = SyncColumns(),
)

@Entity(
    tableName = "projects",
    indices = [Index("user_id"), Index("deleted_at"), Index(value = ["idempotency_key"], unique = true)],
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
    @ColumnInfo("idempotency_key") val idempotencyKey: String? = null,
    @ColumnInfo("external_id") val externalId: String?,
    @Embedded val sync: SyncColumns = SyncColumns(),
)

@Entity(
    tableName = "tags",
    indices = [Index("user_id"), Index("deleted_at"), Index("group_id")],
)
data class TagEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("user_id") val userId: String,
    val name: String,
    val color: Int,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    /**
     * The tag group this tag belongs to, or null if ungrouped.
     * Replaces the dead [parentId] column (flat hierarchy, removed in MR-3).
     *
     * ## Migration (v20, MR-9)
     *
     * - `group_id TEXT DEFAULT NULL` added via auto-migration
     * - `parent_id` dropped via `@DeleteColumn` (was already nullable, safe to remove)
     *
     * @see com.singularity.todo.feature.tags.domain.model.TagGroup
     */
    @ColumnInfo("group_id") val groupId: String? = null,
    @ColumnInfo("sort_order") val sortOrder: Int = 0,
    @ColumnInfo("deleted_at") val deletedAt: Long?,
    @Embedded val sync: SyncColumns = SyncColumns(),
)

/** Row type returned by ProjectDao.watchAllWithCounts — embeds ProjectEntity with aggregate counts. */
data class ProjectWithCountRow(
    @Embedded val project: ProjectEntity,
    @ColumnInfo("total_count") val totalCount: Int,
    @ColumnInfo("completed_count") val completedCount: Int,
)

/**
 * Reminder for a task. Multiple reminders can exist per task.
 *
 * [fireAt] is epoch millis. [type] distinguishes gentle from annoying variants.
 * [recurringPattern] is null for one-shot reminders, or a cron-style expression
 * (e.g. "0 9 * * *" for daily at 9 AM) for recurring ones.
 */
@Entity(
    tableName = "task_reminders",
    primaryKeys = ["user_id", "id"],
    indices = [Index("user_id"), Index("task_id"), Index("fire_at")],
)
data class TaskReminderEntity(
    val id: String,
    @ColumnInfo("task_id") val taskId: String,
    @ColumnInfo("user_id") val userId: String,
    val type: String, // "gentle" | "annoying"
    @ColumnInfo("offset_minutes") val offsetMinutes: Int, // minutes before due (negative = after)
    @ColumnInfo("fire_at") val fireAt: Long, // epoch millis
    @ColumnInfo("recurring_pattern") val recurringPattern: String?, // null or cron expr
    /** SavedAgendaViewId — when a reminder is set from within a saved agenda view, this tracks which view it belongs to, enabling deeplink back to that view when the notification is tapped. */
    @ColumnInfo("view_id") val viewId: String?, // null for reminders not tied to a saved view
    @ColumnInfo("last_fired_at") val lastFiredAt: Long? = null, // epoch millis of last fire; guards against re-firing recurring reminders too soon
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

/**
 * Checklist item (subtask) belonging to a task.
 */
@Entity(
    tableName = "checklist_items",
    primaryKeys = ["id"],
    indices = [Index("task_id")],
)
data class ChecklistItemEntity(
    val id: String,
    @ColumnInfo("task_id") val taskId: String,
    val title: String,
    @ColumnInfo("is_completed") val isCompleted: Boolean = false,
    @ColumnInfo("sort_order") val sortOrder: Int = 0,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

/**
 * Records each AI tool call's token usage for observability and cost tracking.
 * One row per tool invocation, keyed by (profileId, toolName, timestamp).
 */
@Entity(
    tableName = "llm_usage",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["profile_id"]),
        Index(value = ["tool_name"]),
        Index(value = ["model_id"]),
    ],
)
data class LlmUsageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("profile_id") val profileId: String,
    @ColumnInfo("tool_name") val toolName: String,
    @ColumnInfo("model_id") val modelId: String,
    @ColumnInfo("input_tokens") val inputTokens: Int,
    @ColumnInfo("output_tokens") val outputTokens: Int,
    @ColumnInfo("total_tokens") val totalTokens: Int,
    @ColumnInfo("cost_usd_micros") val costUsdMicros: Long?, // null if model unknown
    @ColumnInfo("duration_ms") val durationMs: Long,
    @ColumnInfo("created_at") val createdAt: Long, // epoch millis
    @ColumnInfo("error") val error: String?, // null on success
)

// ─── Profile ─────────────────────────────────────────────────────────────────

/**
 * Persisted user profile. Each profile owns its own tasks, notes, projects, tags,
 * and LLM usage records. Stored in Room for durability and future sync support.
 */
@Entity(
    tableName = "profiles",
    indices = [Index(value = ["name"])],
)
data class ProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("emoji") val emoji: String,
    @ColumnInfo("color_idx") val colorIdx: Int,
    @ColumnInfo("is_default") val isDefault: Boolean,
    @ColumnInfo("created_at") val createdAt: Long, // epoch millis
    @ColumnInfo("updated_at") val updatedAt: Long, // epoch millis
)

// ─── Tag Groups ────────────────────────────────────────────────────────────────

/**
 * Named group of tags (e.g. :work:, :urgent:).
 * Tags reference their group via `tags.group_id`.
 * Projects reference inherited groups via [ProjectInheritedTagGroupCrossRef].
 */
@Entity(
    tableName = "tag_groups",
    indices = [Index("user_id")],
)
data class TagGroupEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("color") val color: Int, // ARGB
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("deleted_at") val deletedAt: Long? = null,
    @Embedded val sync: SyncColumns = SyncColumns(),
)

