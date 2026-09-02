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
    val payloadChecksum: String
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
    val taskTags: Int = 0
)
