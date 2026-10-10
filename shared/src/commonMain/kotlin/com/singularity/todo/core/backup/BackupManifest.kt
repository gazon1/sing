package com.singularity.todo.core.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val appName: String,
    val appVersion: String,
    val createdAtEpochMillis: Long,
    val userIdHash: String,
    val schemaVersion: Int,
    val entityCounts: EntityCounts,
    val payloadChecksum: String,
) {
    val isCompatibleWith: Boolean
        get() = formatVersion <= BackupFormat.FORMAT_VERSION &&
            schemaVersion <= BackupFormat.SCHEMA_VERSION
}

@Serializable
data class EntityCounts(
    val tasks: Int = 0,
    val notes: Int = 0,
    val projects: Int = 0,
    val tags: Int = 0,
    val attachments: Int = 0,
    /**
     * Notes written against attachment text.
     *
     * Counted separately from [attachments] because their absence is silent: a restored
     * archive used to import cleanly with the files back and every note gone, and nothing in
     * the log said so. A count is what makes that visible.
     */
    val attachmentAnnotations: Int = 0,
    val taskTags: Int = 0,
    val taskDependencies: Int = 0,
    /** MR-1: saved agenda views. */
    val agendaViews: Int = 0,
    // MR-2: 8 new entity types
    val taskReminders: Int = 0,
    val projectReminders: Int = 0,
    val checklistItems: Int = 0,
    val tagGroups: Int = 0,
    val projectTagGroups: Int = 0,
    val savedSearches: Int = 0,
    val timeEntries: Int = 0,
    val profiles: Int = 0,
)
