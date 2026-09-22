package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteColor
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

// ─── Task fixtures ─────────────────────────────────────────────────────────────

/**
 * Creates a [Task] with predictable defaults for tests.
 * Override any field via [overrides].
 *
 * Example:
 * ```
 * val task = testTask(id = TaskId.fromString("t1"))
 * val pastTask = testTask(createdAt = Instant.fromEpochMilliseconds(1000))
 * val pinnedTask = testTask(isPinned = true)
 * ```
 */
fun testTask(
    id: TaskId = TaskId.generate(),
    title: String = "Test task",
    description: String? = null,
    priority: TaskPriority = TaskPriority.None,
    kind: TaskKind = TaskKind.Task,
    projectId: ProjectId? = null,
    tags: List<TagId> = emptyList(),
    dueDate: LocalDate? = null,
    dueTime: LocalTime? = null,
    completedAt: Instant? = null,
    someday: Boolean = false,
    archivedAt: Instant? = null,
    isPinned: Boolean = false,
    createdAt: Instant = Instant.fromEpochMilliseconds(0),
    updatedAt: Instant = Instant.fromEpochMilliseconds(0),
    userId: UserId = UserId.anonymous,
    overrides: Task.() -> Unit = {},
): Task = Task(
    id = id,
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId,
    tags = tags,
    dueDate = dueDate,
    dueTime = dueTime,
    completedAt = completedAt,
    someday = someday,
    archivedAt = archivedAt,
    isPinned = isPinned,
    createdAt = createdAt,
    updatedAt = updatedAt,
    userId = userId,
).apply { overrides() }

// ─── Note fixtures ─────────────────────────────────────────────────────────────

/**
 * Creates a [Note] with predictable defaults for tests.
 * Override any field via [overrides].
 *
 * Example:
 * ```
 * val note = testNote(id = NoteId.fromString("n1"), title = "My note")
 * val folder = testNote(isFolder = true)
 * ```
 */
fun testNote(
    id: NoteId = NoteId.generate(),
    title: String = "Test note",
    bodyMarkdown: String? = null,
    bodyHtml: String? = null,
    isFolder: Boolean = false,
    parentNoteId: NoteId? = null,
    isPinned: Boolean = false,
    color: NoteColor? = null,
    sortOrder: Int = 0,
    createdAt: Instant = Instant.fromEpochMilliseconds(0),
    updatedAt: Instant = Instant.fromEpochMilliseconds(0),
    deletedAt: Instant? = null,
    archivedAt: Instant? = null,
    userId: UserId = UserId.anonymous,
    overrides: Note.() -> Unit = {},
): Note = Note(
    id = id,
    userId = userId,
    title = title,
    bodyMarkdown = bodyMarkdown,
    bodyHtml = bodyHtml,
    isFolder = isFolder,
    parentNoteId = parentNoteId,
    isPinned = isPinned,
    pinnedAt = if (isPinned) Instant.fromEpochMilliseconds(0) else null,
    color = color,
    sortOrder = sortOrder,
    wordCount = title.split(" ").size,
    charCount = title.length,
    outgoingLinks = emptyList(),
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    archivedAt = archivedAt,
).apply { overrides() }

