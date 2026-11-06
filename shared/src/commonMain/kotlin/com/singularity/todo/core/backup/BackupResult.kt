package com.singularity.todo.core.backup

data class BackupResult(val manifest: BackupManifest, val destPath: String, val byteSize: Long)

data class RestoreResult(
    val manifest: BackupManifest,
    /**
     * The archive's merged rows, by kind — the base and every change in it, as
     * one payload. A restore that keeps existing rows leaves some of these
     * alone; [keptEntityCounts] says how much of the archive the account already
     * had.
     */
    val entityCounts: EntityCounts,
    val restoredAttachmentCount: Int,
    val missingAttachmentIds: List<String>,
    /**
     * The rows the account already held, left as they were.
     *
     * Empty for an ordinary restore, which overwrites what it carries. Reported
     * so that "there was nothing new in the archive" reads as a result rather
     * than as a restore that did nothing: every count here is a row the account
     * kept.
     */
    val keptEntityCounts: EntityCounts = EntityCounts(),
)

data class BackupMetadata(
    val id: BackupId,
    val path: String,
    val createdAtEpochMillis: Long,
    val sizeBytes: Long,
    val entityCounts: EntityCounts?,
    val remoteUrl: String? = null,
)
