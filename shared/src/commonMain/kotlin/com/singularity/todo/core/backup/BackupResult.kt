package com.singularity.todo.core.backup

data class BackupResult(val manifest: BackupManifest, val destPath: String, val byteSize: Long)

data class RestoreResult(
    val manifest: BackupManifest,
    val entityCounts: EntityCounts,
    val restoredAttachmentCount: Int,
    val missingAttachmentIds: List<String>,
)

data class BackupMetadata(
    val id: BackupId,
    val path: String,
    val createdAtEpochMillis: Long,
    val sizeBytes: Long,
    val entityCounts: EntityCounts?,
    val remoteUrl: String? = null,
)
