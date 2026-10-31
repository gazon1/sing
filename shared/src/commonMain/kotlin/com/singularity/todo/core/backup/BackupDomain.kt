package com.singularity.todo.core.backup

import com.singularity.todo.core.ids.UserId
import okio.Buffer

object BackupDomain {
    fun sha256Hex(bytes: ByteArray): String = Buffer().write(bytes).sha256().hex()

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

    /**
     * What a payload holds, counted.
     *
     * The counts of the **merged** payload are what a restore reports: the base
     * alone says what the snapshot held, and a chain's whole point is that the
     * account moved on since the snapshot was written.
     */
    fun countsOf(payload: BackupPayload): EntityCounts = EntityCounts(
        tasks = payload.tasks.size,
        notes = payload.notes.size,
        projects = payload.projects.size,
        tags = payload.tags.size,
        attachments = payload.attachments.size,
        attachmentAnnotations = payload.attachmentAnnotations.size,
        taskTags = payload.taskTags.size,
        taskDependencies = payload.taskDependencies.size,
        agendaViews = payload.agendaViews.size,
        taskReminders = payload.taskReminders.size,
        projectReminders = payload.projectReminders.size,
        checklistItems = payload.checklistItems.size,
        tagGroups = payload.tagGroups.size,
        projectTagGroups = payload.projectTagGroups.size,
        savedSearches = payload.savedSearches.size,
        timeEntries = payload.timeEntries.size,
        profiles = payload.profiles.size,
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
