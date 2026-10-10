package com.singularity.todo.core.backup

object BackupFormat {
    const val FORMAT_VERSION = 1 // zip layout version

    /** MR-1: +agenda_views entity + taskDependencies (SCHEMA_VERSION 2 was taskDependencies only). */
    const val SCHEMA_VERSION = 3 // entity shape version — full field parity for TaskDto and NoteDto

    /** Oldest schema version this client can restore. Backups with schemaVersion < this are rejected. */
    const val MIN_SUPPORTED_SCHEMA_VERSION = 1
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_PAYLOAD = "payload.json"
    const val DIR_ATTACHMENTS = "attachments/"
    const val APP_NAME = "singularity-todo"
}
