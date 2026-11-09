package com.singularity.todo.feature.nav

import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId

internal fun AppDestination.stableContentKey(): String =
    when (this) {
        is AppDestination.Plans -> "plans"
        is AppDestination.Pomodoro -> "pomodoro"
        is AppDestination.Statistics -> "statistics"
        is AppDestination.Calendar -> "calendar"
        is AppDestination.Notes -> "notes"
        is AppDestination.AiChat -> "ai-chat"
        is AppDestination.ExternalAgent -> "external-agent"
        is AppDestination.Proposals -> "proposals"
        is AppDestination.Search -> "search"
        is AppDestination.Archive -> "archive"
        is AppDestination.Settings -> "settings"
        is AppDestination.AiUsage -> "ai-usage"
        is AppDestination.ProfileSwitcher -> "profile-switcher"
        is AppDestination.AttachAccount -> "attach-account"
        is AppDestination.ChangePassword -> "change-password"
        is AppDestination.MagicLinkConfirm -> "magic-link"
        is AppDestination.RecordDestination -> recordContentKey()
    }

private fun AppDestination.RecordDestination.recordContentKey(): String =
    when (this) {
        is AppDestination.TasksGraph ->
            "tasks-graph:" + start.stableContentKey() + (initialDueDate?.let { ":$it" } ?: "")
        is AppDestination.TasksByProject -> "tasks-by-project:" + projectId.value
        is AppDestination.ProjectEditor -> "project-editor:" + (projectId?.value ?: "new")
        is AppDestination.ProjectDetail -> "project:" + projectId.value
        is AppDestination.AttachmentViewer -> "attachment:" + attachmentId.value
        is AppDestination.ProjectsGraph -> "projects-graph:" + start.stableContentKey()
        is AppDestination.NotesGraph -> "notes-graph:" + start.stableContentKey()
        is AppDestination.CalendarGraph -> "calendar-graph:" + start.stableContentKey()
        is AppDestination.AgendaGraph -> "agenda-graph:" + start.stableContentKey()
    }

internal fun AppDestination.TasksStartRoute.stableContentKey(): String =
    when (this) {
        is AppDestination.TasksStartRoute.Create ->
            taskCreateKey(draftKey = draftKey, projectId = projectId, tagIds = tagIds)
        is AppDestination.TasksStartRoute.Detail -> "task:" + taskId.value
    }

internal fun AppDestination.ProjectsStartRoute.stableContentKey(): String =
    when (this) {
        is AppDestination.ProjectsStartRoute.List -> "projects-list"
        is AppDestination.ProjectsStartRoute.Editor -> "projects-editor:" + (projectId?.value ?: "new")
    }

internal fun AppDestination.NotesStartRoute.stableContentKey(): String =
    when (this) {
        is AppDestination.NotesStartRoute.List -> "notes-list"
        is AppDestination.NotesStartRoute.Preview -> "note:" + noteId.value
        is AppDestination.NotesStartRoute.EditorForTask -> "note-for-task:" + taskId.value
    }

internal fun AppDestination.CalendarStartRoute.stableContentKey(): String =
    when (this) {
        is AppDestination.CalendarStartRoute.Month -> "calendar-month:" + anchor
    }

internal fun AgendaStartRoute.stableContentKey(): String =
    when (this) {
        is AgendaStartRoute.Inbox -> "agenda-inbox"
        is AgendaStartRoute.Today -> "agenda-today"
        is AgendaStartRoute.Upcoming -> "agenda-upcoming"
        is AgendaStartRoute.Project -> "agenda-project:" + projectId.value
        is AgendaStartRoute.Tag -> "agenda-tag:" + tagId.value
        is AgendaStartRoute.Tags -> "agenda-tags:" + tagIds.map { it.value }.sorted().joinToString(",")
        is AgendaStartRoute.SavedAgendaList -> "saved-views"
        is AgendaStartRoute.SavedAgendaResults -> "saved-view:" + viewId.raw
        is AgendaStartRoute.SavedAgendaEdit -> "saved-view-edit:" + viewId.raw
        is AgendaStartRoute.SavedAgendaCreate -> "saved-view-create"
        is AgendaStartRoute.Blocked -> "agenda-blocked"
        is AgendaStartRoute.Stagnant -> "agenda-stagnant"
        is AgendaStartRoute.Overdue -> "agenda-overdue"
    }

internal fun TasksRoute.stableContentKey(): String =
    when (this) {
        is TasksRoute.Detail -> "task:" + taskId.value
        is TasksRoute.Create ->
            taskCreateKey(
                initialDueDate = initialDueDate,
                draftKey = draftKey,
                projectId = projectId,
                tagIds = tagIds,
            )
    }

internal fun taskCreateKey(
    initialDueDate: kotlinx.datetime.LocalDate? = null,
    draftKey: String? = null,
    projectId: ProjectId? = null,
    tagIds: Set<TagId> = emptySet(),
): String =
    "task-create:" + (draftKey ?: "plain") + taskScopeKey(projectId, tagIds) +
        (initialDueDate?.let { ":due:$it" } ?: "")

private fun taskScopeKey(projectId: ProjectId?, tagIds: Set<TagId>): String =
    (projectId?.let { ":project:" + it.value } ?: "") +
        (
            tagIds.takeIf { it.isNotEmpty() }?.let { ids ->
                ":tags:" + ids.map { id -> id.value }.sorted().joinToString(",")
            } ?: ""
        )

internal fun NotesRoute.stableContentKey(): String =
    when (this) {
        is NotesRoute.List -> "notes-list"
        is NotesRoute.Preview -> "note:" + noteId.value
        is NotesRoute.Editor -> "note-editor:" + (noteId?.value ?: taskId?.value ?: "new")
    }

internal fun ProjectsRoute.stableContentKey(): String =
    when (this) {
        is ProjectsRoute.List -> "projects-list"
        is ProjectsRoute.Editor -> "projects-editor:" + (projectId?.value ?: "new")
        is ProjectsRoute.Detail -> "project:" + projectId.value
    }

internal fun CalendarRoute.stableContentKey(): String =
    when (this) {
        is CalendarRoute.Month -> "calendar-month:" + anchor
        is CalendarRoute.Day -> "calendar-day:" + anchor
    }
