package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId

/**
 * The rows the removal-path test starts from: one of every kind.
 *
 * ## Why a file of its own
 *
 * Detekt allows sixteen functions in a class and twenty-five in a file. The rows
 * of seventeen kinds are seventeen builders, and the test that uses them needs
 * a handful of its own — so the builders live here, and the test file stays
 * about the test.
 */
internal fun removalSeedPayload(now: Long, owner: UserId): BackupPayload =
    BackupPayload(
        schemaVersion = BackupFormat.SCHEMA_VERSION,
        tasks = removalTaskRows(now),
        notes = removalNoteRows(now),
        projects = removalProjectRows(now),
        tags = removalTagRows(now),
        tagGroups = removalTagGroupRows(now),
        projectTagGroups = removalProjectTagGroupRows(),
        attachments = removalAttachmentRows(now),
        attachmentAnnotations = removalAnnotationRows(now),
        taskTags = removalTaskTagRows(),
        taskDependencies = removalTaskDependencyRows(),
        agendaViews = removalAgendaViewRows(now),
        taskReminders = removalTaskReminderRows(now),
        projectReminders = removalProjectReminderRows(now),
        checklistItems = removalChecklistRows(now),
        savedSearches = removalSavedSearchRows(now),
        timeEntries = removalTimeEntryRows(now),
        profiles = removalProfileRows(now, owner),
    )

/** The task, and the task the dependency hangs off. */
internal fun removalTaskRows(now: Long) = listOf(
    TaskDto(id = "task-1", title = "A task", createdAt = now, updatedAt = now),
    TaskDto(id = "task-2", title = "Another task", createdAt = now, updatedAt = now),
)

/** The note. */
internal fun removalNoteRows(now: Long) =
    listOf(NoteDto(id = "note-1", title = "A note", createdAt = now, updatedAt = now))

/** The project. */
internal fun removalProjectRows(now: Long) = listOf(
    ProjectDto(
        id = "project-1",
        name = "A project",
        color = 0,
        createdAt = now,
        updatedAt = now,
    ),
)

/** The project reminders. */
internal fun removalProjectReminderRows(now: Long) = listOf(
    ProjectReminderDto(
        id = "project-reminder-1",
        projectId = "project-1",
        fireAt = now,
        createdAt = now,
        updatedAt = now,
    ),
)

/** The tag. */
internal fun removalTagRows(now: Long) =
    listOf(TagDto(id = "tag-1", name = "A tag", color = 0, createdAt = now, updatedAt = now))

/** The tag groups. */
internal fun removalTagGroupRows(now: Long) = listOf(
    TagGroupDto(
        id = "tag-group-1",
        name = "A group",
        color = 0,
        createdAt = now,
        updatedAt = now,
    ),
)

/** The link between the project and the tag group it inherits. */
internal fun removalProjectTagGroupRows() =
    listOf(ProjectTagGroupDto(projectId = "project-1", tagGroupId = "tag-group-1"))

/** The attachment. */
internal fun removalAttachmentRows(now: Long) = listOf(
    AttachmentDto(
        id = "attachment-1",
        taskId = "task-1",
        type = "file",
        createdAt = now,
        updatedAt = now,
    ),
)

/** The annotation on the attachment. */
internal fun removalAnnotationRows(now: Long) = listOf(
    AttachmentAnnotationDto(
        id = "annotation-1",
        attachmentId = "attachment-1",
        rangeStart = 0,
        rangeEnd = 1,
        quote = "a quote",
        note = "a note",
        createdAt = now,
        updatedAt = now,
    ),
)

/** The link between the task and the tag. */
internal fun removalTaskTagRows() = listOf(TaskTagDto(taskId = "task-1", tagId = "tag-1"))

/** The link between the task and the task it depends on. */
internal fun removalTaskDependencyRows() =
    listOf(TaskDependencyDto(taskId = "task-1", dependsOnTaskId = "task-2"))

/** The saved agenda view. */
internal fun removalAgendaViewRows(now: Long) = listOf(
    AgendaViewDto(
        id = "view-1",
        name = "A view",
        sectionsJson = "[]",
        createdAt = now,
        updatedAt = now,
    ),
)

/** The task's reminder. */
internal fun removalTaskReminderRows(now: Long) = listOf(
    TaskReminderDto(
        id = "reminder-1",
        taskId = "task-1",
        type = "gentle",
        offsetMinutes = 0,
        fireAt = now,
        createdAt = now,
        updatedAt = now,
    ),
)

/** The task's checklist item. */
internal fun removalChecklistRows(now: Long) = listOf(
    ChecklistItemDto(
        id = "checklist-1",
        taskId = "task-1",
        title = "A step",
        createdAt = now,
        updatedAt = now,
    ),
)

/** The saved search. */
internal fun removalSavedSearchRows(now: Long) = listOf(
    SavedSearchDto(
        id = "search-1",
        name = "A search",
        queryString = "a query",
        createdAt = now,
        updatedAt = now,
    ),
)

/** The time entry against the task. */
internal fun removalTimeEntryRows(now: Long) = listOf(
    TimeEntryDto(
        id = "time-1",
        taskId = "task-1",
        startedAt = now,
        kind = "Work",
        source = "Manual",
        createdAt = now,
        updatedAt = now,
    ),
)

/** The profile the rows belong to. */
internal fun removalProfileRows(now: Long, owner: UserId) = listOf(
    ProfileDto(
        id = "profile-1",
        name = "A profile",
        emoji = "🙂",
        colorIdx = 0,
        isDefault = true,
        createdAt = now,
        updatedAt = now,
        userId = owner.value,
    ),
)
