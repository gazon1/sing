package com.singularity.todo.core.backup

object BackupFormat {
    const val FORMAT_VERSION = 1 // zip layout version

    /**
     * MR-2: +8 new entity types:
     * task_reminders, project_reminders, checklist_items, tag_groups,
     * project_tag_groups, saved_searches, time_entries, profiles.
     */
    const val SCHEMA_VERSION = 4 // entity shape version — full field parity for TaskDto and NoteDto

    /** Oldest schema version this client can restore. Backups with schemaVersion < this are rejected. */
    const val MIN_SUPPORTED_SCHEMA_VERSION = 1
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_PAYLOAD = "payload.json"
    const val DIR_ATTACHMENTS = "attachments/"
    const val APP_NAME = "singularity-todo"
}
