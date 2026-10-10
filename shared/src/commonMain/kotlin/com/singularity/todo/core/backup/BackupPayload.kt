package com.singularity.todo.core.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupPayload(
    val schemaVersion: Int,
    val tasks: List<TaskDto> = emptyList(),
    val notes: List<NoteDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    /** Notes written against spans of a text attachment — see ADR `2026-10-07-annotation-anchor-model`. */
    val attachmentAnnotations: List<AttachmentAnnotationDto> = emptyList(),
    val taskTags: List<TaskTagDto> = emptyList(),
    /** MR-1: task dependency edges (task_id → depends_on_task_id). */
    val taskDependencies: List<TaskDependencyDto> = emptyList(),
    /** MR-1: saved agenda views. */
    val agendaViews: List<AgendaViewDto> = emptyList(),
    /** MR-2: task-level reminders. */
    val taskReminders: List<TaskReminderDto> = emptyList(),
    /** MR-2: project-level reminders. */
    val projectReminders: List<ProjectReminderDto> = emptyList(),
    /** MR-2: checklist items (subtasks) scoped through their owning task's user. */
    val checklistItems: List<ChecklistItemDto> = emptyList(),
    /** MR-2: tag groups. */
    val tagGroups: List<TagGroupDto> = emptyList(),
    /** MR-2: project ↔ tag group inheritance edges. */
    val projectTagGroups: List<ProjectTagGroupDto> = emptyList(),
    /** MR-2: saved search queries. */
    val savedSearches: List<SavedSearchDto> = emptyList(),
    /** MR-2: time tracking entries. */
    val timeEntries: List<TimeEntryDto> = emptyList(),
    // profiles: tracked separately — cross-profile restore needs a decision about
    // which profile becomes active (issue #302 note).
)
