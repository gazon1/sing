package com.singularity.todo.core.wipe

/**
 * The tables a profile reset reaches, and the call that reaches each.
 *
 * ## Why this list exists in code
 *
 * A list of exceptions is dangerous for one reason: nobody notices when it goes
 * stale. A table added to the schema is not a row in a document — it is a row in
 * a database — so "the reset reaches the whole profile" stays true in the
 * prose long after it stops being true in the code.
 *
 * So the list is stated once, here, and checked by
 * `scripts/ci/check-profile-reset-inventory.py`: that gate reads this file, the Room
 * entity definitions and `docs/architecture/wipe-inventory.md`, and fails when
 * they disagree — a table nobody decided about, a table the document names but
 * the schema no longer has, a table the code wipes and the document calls "not
 * profile content".
 *
 * ## How to read it
 *
 * The value is the call exactly as it is written in [ProfileContentResetter], so
 * the gate can check that the reset really makes that call, and that the call
 * really issues a `DELETE FROM` scoped to the profile.
 */
object ProfileWipePlan {

    /**
     * Every table the reset deletes, mapped to the call that deletes it.
     *
     * Several tables share one call: a repository's bulk delete takes the rows
     * that hang off its own rows with it (the task's tag references, its
     * dependencies, its checklist items), and those are the same delete.
     */
    val WIPED: Map<String, String> = mapOf(
        "agenda_views" to "savedAgendaViewsRepository.deleteAllForCurrentProfile",
        "ai_proposal" to "proposalDao.deleteAllForUser",
        "ai_proposal_item" to "proposalItemDao.deleteAllForUser",
        "attachment_annotations" to "annotationDao.deleteAllForUser",
        "attachments" to "attachmentDao.deleteAllForUser",
        "calendar_import_event" to "calendarImportEventDao.deleteAllForUser",
        "calendar_sync_task_map" to "calendarSyncTaskMapDao.clearAll",
        "checklist_items" to "taskRepository.deleteAllForCurrentProfile",
        "google_event_shadow" to "googleEventShadowDao.deleteAllForUser",
        "notes" to "notesRepository.deleteAllForCurrentProfile",
        "project_reminders" to "projectReminderDao.deleteAllForUser",
        "projects" to "projectsRepository.deleteAllForCurrentProfile",
        "project_tag_groups" to "projectsRepository.deleteAllForCurrentProfile",
        "saved_searches" to "savedSearchDao.deleteAllForUser",
        "tag_groups" to "tagGroupRepository.deleteAllForCurrentProfile",
        "tags" to "tagsRepository.deleteAllForCurrentProfile",
        "task_dependencies" to "taskRepository.deleteAllForCurrentProfile",
        "task_reminders" to "reminderDao.deleteAllForUser",
        "tasks" to "taskRepository.deleteAllForCurrentProfile",
        "task_tags" to "taskRepository.deleteAllForCurrentProfile",
        "time_entries" to "timeEntryDao.deleteAllForUser",
    )
}
