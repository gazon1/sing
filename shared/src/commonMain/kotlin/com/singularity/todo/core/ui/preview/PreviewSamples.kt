@file:Suppress("MemberVisibilityCanBePrivate")

package com.singularity.todo.core.ui.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentType
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUi
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

// ===== Preview theme wrapper =====

/**
 * Wraps a preview content in [SingularityTheme] with optional dark/light mode
 * and accent color override.
 *
 * @param darkTheme  If true, uses the dark color scheme; otherwise light.
 * @param accent     Accent color used by [SingularityAccents].
 * @param useSurface If true, wraps content in a [Surface] with the background color.
 *                   Pass false when the preview root already contains a Scaffold
 *                   (which provides its own background via MaterialTheme.colorScheme.background).
 */
@Composable
internal fun PreviewThemed(
    darkTheme: Boolean = false,
    accent: SingularityAccents = SingularityAccents.Blue,
    useSurface: Boolean = true,
    content: @Composable () -> Unit,
) {
    SingularityTheme(darkTheme = darkTheme, accent = accent) {
        if (useSurface) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        } else {
            content()
        }
    }
}

// ===== Shared sample data builders =====

/**
 * Single source of truth for all preview sample data in the module.
 * Use these builders inside @Preview composables instead of duplicating
 * construction logic across files.
 *
 * All [Instant] fields use the same "now" timestamp so previews are
 * consistent within a single render pass.
 */
internal object PreviewSamples {
    // Use epoch-0 so we don't depend on Clock.System (unavailable in some KMP targets)
    private val now: Instant = Instant.fromEpochMilliseconds(0)
    val today: LocalDate = LocalDate(2026, 9, 6)
    val userId: UserId = UserId.anonymous
    private val projectUserId: String = UserId.anonymous.value

    fun task(
        id: String = "t1",
        title: String = "Buy groceries",
        priority: TaskPriority = TaskPriority.Medium,
        completed: Boolean = false,
        pinned: Boolean = false,
        dueDate: LocalDate? = today,
        kind: TaskKind = TaskKind.Task,
    ): Task = Task(
        id = TaskId(id),
        title = title,
        priority = priority,
        kind = kind,
        dueDate = dueDate,
        completedAt = if (completed) now else null,
        isPinned = pinned,
        createdAt = now,
        updatedAt = now,
        userId = userId,
    )

    fun project(
        id: String = "p1",
        name: String = "Inbox",
        color: Int = 0xFF2196F3.toInt(),
        description: String? = null,
        icon: String? = null,
        parentId: ProjectId? = null,
    ): Project = Project(
        id = ProjectId(id),
        name = name,
        color = color,
        description = description,
        icon = icon,
        parentId = parentId,
        createdAt = now,
        updatedAt = now,
        userId = UserId(projectUserId),
    )

    fun tag(
        id: String = "tg1",
        name: String = "work",
        color: Int = 0xFFE91E63.toInt(),
    ): Tag = Tag(
        id = TagId(id),
        name = name,
        color = color,
        createdAt = now,
        updatedAt = now,
        userId = projectUserId,
    )

    fun note(
        id: String = "n1",
        title: String = "Ideas",
        body: String = "Hello **markdown**",
    ): Note = Note(
        id = NoteId(id),
        userId = userId,
        title = title,
        bodyMarkdown = body,
        bodyHtml = "<p>Hello <strong>markdown</strong></p>",
        createdAt = now,
        updatedAt = now,
    )

    /** Note in a folder (non-leaf). */
    fun folderNote(
        id: String = "n2",
        title: String = "Work",
    ): Note = Note(
        id = NoteId(id),
        userId = userId,
        title = title,
        bodyMarkdown = null,
        bodyHtml = null,
        isFolder = true,
        parentNoteId = null,
        createdAt = now,
        updatedAt = now,
    )

    /** Archived note (soft-deleted, visible in archive). */
    fun archivedNote(
        id: String = "n3",
        title: String = "Old Note",
        body: String = "This note was archived.",
    ): Note = Note(
        id = NoteId(id),
        userId = userId,
        title = title,
        bodyMarkdown = body,
        bodyHtml = "<p>This note was archived.</p>",
        createdAt = now,
        updatedAt = now,
        archivedAt = now,
    )

    fun reminder(offsetMinutes: Int = 15): Reminder = Reminder(
        id = ReminderId.generate(),
        taskId = TaskId("t1"),
        userId = userId,
        type = ReminderType.Gentle,
        offsetMinutes = offsetMinutes,
        fireAt = 0L,
        recurringPattern = null,
    )

    fun attachment(
        type: AttachmentType = AttachmentType.File,
        title: String = "report.pdf",
    ): Attachment = Attachment(
        id = AttachmentId.generate(),
        taskId = TaskId("t1"),
        userId = userId,
        type = type,
        title = title,
        fileSizeBytes = 12_345L,
        mimeType = "application/pdf",
        createdAt = now,
        updatedAt = now,
    )

    fun checklistItem(
        title: String = "Sub-task",
        done: Boolean = false,
    ): ChecklistItem = ChecklistItem(
        id = ChecklistItemId.generate(),
        taskId = "t1",
        title = title,
        isCompleted = done,
    )

    fun taskDetailUi(
        task: Task = task(),
        project: Project? = null,
        tags: List<Tag> = emptyList(),
        checklist: List<ChecklistItem> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        subtasks: List<Task> = emptyList(),
    ): TaskDetailUi = TaskDetailUi(
        task = task,
        project = project,
        tags = tags,
        checklist = checklist,
        reminders = reminders,
        attachments = attachments,
        subtasks = subtasks,
    )
}

// ===== PreviewParameterProvider for enum types =====

/**
 * Provides all [TaskPriority] values as a sequence for use with
 * [@PreviewParameter][androidx.compose.ui.tooling.preview.PreviewParameter].
 *
 * Usage:
 * ```
 * @Preview
 * @Composable
 * private fun PriorityChipAllPreview(
 *     @PreviewParameter(TaskPriorityProvider::class) priority: TaskPriority,
 * ) = PreviewThemed { PriorityChip(priority = priority) }
 * ```
 * Android Studio renders one preview cell per enum entry.
 */
// DISABLED: PreviewParameterProvider requires ui-tooling which may not be available
// in all KMP targets. Use individual preview functions instead.
// internal class TaskPriorityProvider : androidx.compose.ui.tooling.preview.PreviewParameterProvider<TaskPriority> {
//     override val values: Sequence<TaskPriority> = TaskPriority.entries.asSequence()
//     override val count: Int = TaskPriority.entries.size
// }
