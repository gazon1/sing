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
    // MR-2: 8 new entity types
    val taskReminders: List<TaskReminderDto> = emptyList(),
    val projectReminders: List<ProjectReminderDto> = emptyList(),
    val checklistItems: List<ChecklistItemDto> = emptyList(),
    val tagGroups: List<TagGroupDto> = emptyList(),
    val projectTagGroups: List<ProjectTagGroupDto> = emptyList(),
    val savedSearches: List<SavedSearchDto> = emptyList(),
    val timeEntries: List<TimeEntryDto> = emptyList(),
    val profiles: List<ProfileDto> = emptyList(),
    /**
     * The rows that were removed, by kind.
     *
     * ## Keys, not rows
     *
     * A removal is the **absence** of a row, so there is nothing to carry: what
     * travels is the row's key, and the key is what says which row is not there
     * any more. The kinds are the ones the row index uses, so a removal is looked
     * up under the same key the row itself would have been.
     *
     * ## Empty in a snapshot
     *
     * A base is the state, and a state says what is there — not what used to be.
     * Only a change carries removals.
     *
     * ## Optional
     *
     * The field has a default, so an archive written before it existed still
     * reads: the rows it holds are the whole story, and nothing was removed.
     */
    val removals: Map<BackupEntityKind, List<String>> = emptyMap(),
)
