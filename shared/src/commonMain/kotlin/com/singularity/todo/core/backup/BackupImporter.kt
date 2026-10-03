package com.singularity.todo.core.backup

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.serialization.StableJson
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

class BackupImporter(
    private val log: Logger,
    private val taskDao: TaskDao,
    private val noteDao: NoteDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val attachmentStorage: AttachmentStorage,
    private val codec: BackupCodec,
    private val clock: Clock,
    private val createFileSource: FileSourceFactory,
) {
    private val json = StableJson

    suspend fun import(options: ImportOptions): Result<RestoreResult> = runCatching {
        val now = clock.now().toEpochMilliseconds()

        // 1. Read zip — use FileSource so content:// URIs from SAF work on Android
        val read = codec.importFromSource(createFileSource(options.sourcePath)).getOrThrow()

        // 2. Validate manifest
        val manifest: BackupManifest = json.decodeFromString(
            BackupManifest.serializer(),
            read.manifestBytes.decodeToString(),
        )
        BackupDomain.validateManifest(manifest, read.payloadBytes).getOrThrow()

        // 3. Decode payload
        val payload: BackupPayload = json.decodeFromString(
            BackupPayload.serializer(),
            read.payloadBytes.decodeToString(),
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
        //
        // LAYER EXCEPTION — this writes DAOs directly instead of going through
        // repositories, and that is deliberate rather than an oversight.
        //
        // Every user-scoped repository resolves its target from the *ambient*
        // profile (`ProfileAwareCurrentUser.scopedUserId`) and its writes are
        // constrained to that user: `assertCanWrite` rejects a foreign `userId`,
        // and the `*ForUser` DAO queries filter on it. An import, by contrast,
        // targets an arbitrary `options.targetUserId` — restoring a backup on
        // behalf of another account is the whole point of the feature. Routing
        // this through the repositories would either be rejected by the guard or
        // would require temporarily switching the active profile, which is a
        // user-visible side effect for the duration of the import.
        //
        // It is also why the two unscoped `@Upsert` cross-ref methods in TaskDao
        // are retained: `upsertTagCrossRef` and `upsertDependency` have no
        // user-scoped counterpart because only this path needs them.
        //
        // Tracked as known debt in docs/decisions/2026-09-27-write-layer-soundness.md
        // (ledger #2). The proper resolution is a dedicated bulk-import port that
        // takes an explicit userId, not a pass through the ambient-scoped
        // repositories.
        for (task in migratedPayload.tasks) {
            taskDao.upsert(
                task.toEntity(options.targetUserId.value).copy(
                    updatedAt = now,
                    createdAt = task.createdAt,
                ),
            )
        }
        for (note in migratedPayload.notes) {
            noteDao.upsert(
                note.toEntity(options.targetUserId.value).copy(
                    updatedAt = now,
                    createdAt = note.createdAt,
                ),
            )
        }
        for (project in migratedPayload.projects) {
            projectDao.upsert(
                project.toEntity(options.targetUserId.value).copy(
                    updatedAt = now,
                    createdAt = project.createdAt,
                ),
            )
        }
        for (tag in migratedPayload.tags) {
            tagDao.upsert(
                tag.toEntity(options.targetUserId.value).copy(
                    updatedAt = now,
                    createdAt = tag.createdAt,
                ),
            )
        }
        for (taskTag in migratedPayload.taskTags) {
            taskDao.upsertTagCrossRef(taskTag.toEntity())
        }
        for (dep in migratedPayload.taskDependencies) {
            taskDao.upsertDependency(dep.toEntity())
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
                } catch (e: CancellationException) {
                    throw e
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
            missingAttachmentIds = missingIds,
        )
    }
}
