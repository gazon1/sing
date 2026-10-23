package com.singularity.todo.feature.nav

/**
 * The key an entry's saved state is filed under.
 *
 * ## Why the default key is not good enough
 *
 * nav3 files an entry's saved state under its *content key*. The default is
 * `"$key:${key::class}"` — the route's `toString()` and the route's class. A route's
 * `toString()` is generated from its fields, so the moment a route grows a field the
 * key changes: the saved state of an open screen is filed under a name the next build
 * does not ask for, and the screen comes back empty after an update. nav3 1.2.0
 * changed the default from `key.toString()` to `"$key:${key::class}"` for its own
 * reasons; neither form survives a change to the route's shape.
 *
 * A content key that says what the screen *is* does survive it: the task's id, the
 * note's id, the saved view's id. Those do not change when the route gains a field.
 *
 * ## The rules
 *
 * 1. The key names the screen, not the route: `task:t1`, not
 *    `Detail(taskId=t1, sourceViewId=null)`.
 * 2. Two entries that show different records get different keys; two entries that
 *    show the same record share one — that is what the saved state means.
 * 3. Every route hierarchy is covered by an exhaustive `when`. A new route variant
 *    does not compile until it has a key, so a screen cannot join the app without one.
 *
 * @see AppDestination.stableContentKey
 */
internal fun AppDestination.stableContentKey(): String = when (this) {
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

    // The token is a secret: it does not belong in a key that is written into the
    // saved state. One magic link at a time is the only case there is.
    is AppDestination.MagicLinkConfirm -> "magic-link"

    // The destinations that stand for a record — a task, a project, an attachment —
    // or for a nested graph. Their keys carry the record's id, so they are computed
    // in [recordContentKey]; the `when` above stays exhaustive over the hierarchy
    // and stays under the complexity limit.
    //
    // The group is a type ([AppDestination.RecordDestination]), so it is named once:
    // a `when` over it is exhaustive by the compiler, and a destination that joins
    // the group does not compile until it has a key.
    is AppDestination.RecordDestination -> recordContentKey()
}

/**
 * The key of a destination that stands for a record or for a nested graph.
 *
 * Split out of [AppDestination.stableContentKey] because one `when` over every
 * destination does not fit under the complexity limit. The outer `when` still names
 * every variant of [AppDestination], so a new destination does not compile until it
 * is classified there.
 *
 * There is no `else` branch, and that is the point: the receiver is
 * [AppDestination.RecordDestination], a sealed type, so the compiler knows every
 * destination of the group and refuses to compile until each one has a key. The
 * membership of the group used to be written twice — here and in the outer `when` —
 * and the two could drift apart, which reached the runtime as "no content key
 * for ...".
 */
private fun AppDestination.RecordDestination.recordContentKey(): String = when (this) {
    // The date is part of the key: two new-task screens that prefill different dates
    // are two different screens, and one screen's draft is not the other's.
    is AppDestination.TasksGraph -> "tasks-graph:" + start.stableContentKey() +
        (initialDueDate?.let { ":$it" } ?: "")

    is AppDestination.TasksByProject -> "tasks-by-project:" + projectId.value

    is AppDestination.ProjectEditor -> "project-editor:" + (projectId?.value ?: "new")

    is AppDestination.ProjectDetail -> "project:" + projectId.value

    is AppDestination.AttachmentViewer -> "attachment:" + attachmentId.value

    is AppDestination.ProjectsGraph -> "projects-graph:" + start.stableContentKey()

    is AppDestination.NotesGraph -> "notes-graph:" + start.stableContentKey()

    is AppDestination.CalendarGraph -> "calendar-graph:" + start.stableContentKey()

    is AppDestination.AgendaGraph -> "agenda-graph:" + start.stableContentKey()
}

/** @see AppDestination.stableContentKey */
internal fun AppDestination.TasksStartRoute.stableContentKey(): String = when (this) {
    is AppDestination.TasksStartRoute.Create -> "task-create:" + (draftKey ?: "plain")

    // The task's id, and nothing else: the view the task was opened from is context,
    // not identity, and a key that carried it would change when that context does.
    is AppDestination.TasksStartRoute.Detail -> "task:" + taskId.value
}

/** @see AppDestination.stableContentKey */
internal fun AppDestination.ProjectsStartRoute.stableContentKey(): String = when (this) {
    is AppDestination.ProjectsStartRoute.List -> "projects-list"
    is AppDestination.ProjectsStartRoute.Editor -> "projects-editor:" + (projectId?.value ?: "new")
}

/** @see AppDestination.stableContentKey */
internal fun AppDestination.NotesStartRoute.stableContentKey(): String = when (this) {
    is AppDestination.NotesStartRoute.List -> "notes-list"
    is AppDestination.NotesStartRoute.Preview -> "note:" + noteId.value
    is AppDestination.NotesStartRoute.EditorForTask -> "note-for-task:" + taskId.value
}

/** @see AppDestination.stableContentKey */
internal fun AppDestination.CalendarStartRoute.stableContentKey(): String = when (this) {
    is AppDestination.CalendarStartRoute.Month -> "calendar-month:" + anchor
}

/** @see AppDestination.stableContentKey */
internal fun AgendaStartRoute.stableContentKey(): String = when (this) {
    is AgendaStartRoute.Inbox -> "agenda-inbox"

    is AgendaStartRoute.Today -> "agenda-today"

    is AgendaStartRoute.Upcoming -> "agenda-upcoming"

    is AgendaStartRoute.Project -> "agenda-project:" + projectId.value

    is AgendaStartRoute.Tag -> "agenda-tag:" + tagId.value

    // The ids are sorted: a set has no order of its own, and a key that changed
    // with the iteration order would be a key that changes for no reason.
    is AgendaStartRoute.Tags -> "agenda-tags:" + tagIds.map { it.value }.sorted().joinToString(",")

    is AgendaStartRoute.SavedAgendaList -> "saved-views"

    is AgendaStartRoute.SavedAgendaResults -> "saved-view:" + viewId.raw

    is AgendaStartRoute.SavedAgendaEdit -> "saved-view-edit:" + viewId.raw

    is AgendaStartRoute.SavedAgendaCreate -> "saved-view-create"

    is AgendaStartRoute.Blocked -> "agenda-blocked"

    is AgendaStartRoute.Stagnant -> "agenda-stagnant"

    is AgendaStartRoute.Overdue -> "agenda-overdue"
}

/** @see AppDestination.stableContentKey */
internal fun TasksRoute.stableContentKey(): String = when (this) {
    is TasksRoute.Detail -> "task:" + taskId.value

    is TasksRoute.Create -> "task-create:" + (initialDueDate?.toString() ?: "plain") +
        ":" + (draftKey ?: "")
}

/** @see AppDestination.stableContentKey */
internal fun NotesRoute.stableContentKey(): String = when (this) {
    is NotesRoute.List -> "notes-list"
    is NotesRoute.Preview -> "note:" + noteId.value
    is NotesRoute.Editor -> "note-editor:" + (noteId?.value ?: taskId?.value ?: "new")
}

/** @see AppDestination.stableContentKey */
internal fun ProjectsRoute.stableContentKey(): String = when (this) {
    is ProjectsRoute.List -> "projects-list"
    is ProjectsRoute.Editor -> "projects-editor:" + (projectId?.value ?: "new")
    is ProjectsRoute.Detail -> "project:" + projectId.value
}

/** @see AppDestination.stableContentKey */
internal fun CalendarRoute.stableContentKey(): String = when (this) {
    is CalendarRoute.Month -> "calendar-month:" + anchor
    is CalendarRoute.Day -> "calendar-day:" + anchor
}
