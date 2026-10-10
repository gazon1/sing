package com.singularity.todo.core.backup

import co.touchlab.kermit.Logger
import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.attachments.AttachmentStorage
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationDao
import com.singularity.todo.core.database.AgendaViewDao
import com.singularity.todo.core.database.ChecklistDao
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.SavedSearchDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TagGroupDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.timetracking.data.TimeEntryDao
import com.singularity.todo.core.database.ReminderDao
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable

class BackupImporter(
    private val codec: BackupCodec,
    private val createFileSource: FileSourceFactory,
    private val bulkImportPort: BulkImportPort,
) {
    private val json = StableJson

    /**
     * Replaces the historical layer exception that wrote DAOs directly.
     *
     * The restore flow:
     * 1. Read + decode zip (codec and FileSource remain here — platform-specific)
     * 2. Validate manifest
     * 3. Decode payload
     * 4. Apply migrations
     * 5. Delegate entity writes to [BulkImportPort] — the explicit-userId port that
     *    replaces the ambient-profile-scoped repository path
     *
     * The attachment-file loop also stays here (attachmentStorage is platform-specific
     * via the FileSystem port), but entity restoration proper is fully delegated.
     */
    suspend fun import(options: ImportOptions): Result<RestoreResult> = runCatchingCancellable {
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

        // 5. Delegate entity writes to BulkImportPort
        bulkImportPort.restore(
            payload = migratedPayload,
            manifest = manifest,
            targetUserId = options.targetUserId,
            attachmentData = read.attachments,
            overwriteExisting = options.overwriteExisting,
        ).getOrThrow()
    }
}
