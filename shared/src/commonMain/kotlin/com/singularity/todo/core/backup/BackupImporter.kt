package com.singularity.todo.core.backup

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.platform.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class BackupImporter(
    private val log: Logger,
    private val taskDao: TaskDao,
    private val noteDao: NoteDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val attachmentStorage: AttachmentStorage,
    private val codec: BackupCodec,
    private val clock: Clock,
    private val fs: FileSystem
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun import(options: ImportOptions): Result<RestoreResult> = runCatching {
        val now = clock.now().toEpochMilliseconds()

        // 1. Read zip
        val read = codec.import(options.sourcePath, fs).getOrThrow()

        // 2. Validate manifest
        val manifest: BackupManifest = json.decodeFromString(
            BackupManifest.serializer(),
            read.manifestBytes.decodeToString()
        )
        BackupDomain.validateManifest(manifest, read.payloadBytes).getOrThrow()

        // 3. Decode payload
        val payload: BackupPayload = json.decodeFromString(
            BackupPayload.serializer(),
            read.payloadBytes.decodeToString()
        )

        // 4. Apply migrations if needed
        val migratedPayload = if (payload.schemaVersion < BackupFormat.SCHEMA_VERSION) {
            val jsonObj = json.parseToJsonElement(read.payloadBytes.decodeToString()).jsonObject
            val migrated = BackupMigrations.migrate(jsonObj, payload.schemaVersion)
            val migratedBytes = json.encodeToString(migrated).encodeToByteArray()
            json.decodeFromString(BackupPayload.serializer(), migratedBytes.decodeToString())
        } else {
            payload
        }

        // 5. Restore entities
        for (task in migratedPayload.tasks) {
            taskDao.upsert(task.toEntity(options.targetUserId.value).copy(
                updatedAt = now, createdAt = task.createdAt
            ))
        }
        for (note in migratedPayload.notes) {
            noteDao.upsert(note.toEntity(options.targetUserId.value).copy(
                updatedAt = now, createdAt = note.createdAt
            ))
        }
        for (project in migratedPayload.projects) {
            projectDao.upsert(project.toEntity(options.targetUserId.value).copy(
                updatedAt = now, createdAt = project.createdAt
            ))
        }
        for (tag in migratedPayload.tags) {
            tagDao.upsert(tag.toEntity(options.targetUserId.value).copy(
                updatedAt = now, createdAt = tag.createdAt
            ))
        }
        for (taskTag in migratedPayload.taskTags) {
            taskDao.upsertTagCrossRef(taskTag.toEntity())
        }

        // 6. Restore attachment files
        var restoredCount = 0
        val missingIds = mutableListOf<String>()

        for (att in migratedPayload.attachments) {
            val entryName = "${att.id}.dat"
            val bytes = read.attachments[entryName]
            if (bytes != null) {
                try {
                    val ext = att.mimeType?.substringAfterLast('/') ?: ""
                    attachmentStorage.saveBytes(att.taskId, att.id, bytes, ext)
                    restoredCount++
                } catch (e: Exception) {
                    log.w(e) { "Attachment restore failed [id=${att.id}]" }
                    missingIds.add(att.id)
                }
            } else {
                missingIds.add(att.id)
            }
        }

        RestoreResult(
            manifest = manifest,
            entityCounts = manifest.entityCounts,
            restoredAttachmentCount = restoredCount,
            missingAttachmentIds = missingIds
        )
    }
}
