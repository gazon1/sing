package com.singularity.todo.core.backup

import com.singularity.todo.core.files.FileChecksum
import com.singularity.todo.core.ids.UserId

object BackupDomain {
    fun sha256Hex(bytes: ByteArray): String = FileChecksum.sha256(bytes)

    fun extractUserIdHash(userId: UserId): String = sha256Hex(userId.value.encodeToByteArray())

    fun buildManifest(
        appVersion: String,
        nowEpochMillis: Long,
        userId: UserId,
        payloadBytes: ByteArray,
        counts: EntityCounts,
    ): BackupManifest = BackupManifest(
        formatVersion = BackupFormat.FORMAT_VERSION,
        appName = BackupFormat.APP_NAME,
        appVersion = appVersion,
        createdAtEpochMillis = nowEpochMillis,
        userIdHash = extractUserIdHash(userId),
        schemaVersion = BackupFormat.SCHEMA_VERSION,
        entityCounts = counts,
        payloadChecksum = sha256Hex(payloadBytes),
    )

    fun validateManifest(manifest: BackupManifest, payloadBytes: ByteArray): Result<Unit> = runCatching {
        if (manifest.formatVersion > BackupFormat.FORMAT_VERSION) {
            throw BackupPayloadError.UnsupportedFormatVersion(manifest.formatVersion)
        }
        if (manifest.schemaVersion > BackupFormat.SCHEMA_VERSION) {
            throw BackupPayloadError.UnsupportedSchemaVersion(manifest.schemaVersion)
        }
        if (manifest.schemaVersion < BackupFormat.MIN_SUPPORTED_SCHEMA_VERSION) {
            throw BackupPayloadError.SchemaTooOld(manifest.schemaVersion)
        }
        val actualChecksum = sha256Hex(payloadBytes)
        if (manifest.payloadChecksum != actualChecksum) {
            throw BackupPayloadError.ChecksumMismatch(manifest.payloadChecksum, actualChecksum)
        }
    }
}
