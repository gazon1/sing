package com.singularity.todo.core.backup

import com.singularity.todo.core.attachments.AttachmentDao
import com.singularity.todo.core.database.NoteDao
import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.TagDao
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.serialization.StableJson

class BackupExporter(
    private val taskDao: TaskDao,
    private val noteDao: NoteDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val attachmentDao: AttachmentDao,
    private val codec: BackupCodec,
    private val clock: Clock,
    private val fs: FileSystem,
) {
    private val json = StableJson

    suspend fun export(options: ExportOptions): Result<BackupResult> = runCatching {
        val now = clock.now().toEpochMilliseconds()

        // 1. Read all entities for user
        val tasks = taskDao.listAllForUser(options.userId.value)
        val notes = noteDao.listAllForUser(options.userId.value)
        val projects = projectDao.listAllForUser(options.userId.value)
        val tags = tagDao.listAllForUser(options.userId.value)
        val attachments = attachmentDao.listAllForUser(options.userId.value)

        // 2. Map to DTOs
        val payload = BackupPayload(
            schemaVersion = BackupFormat.SCHEMA_VERSION,
            tasks = tasks.map { it.toDto() },
            notes = notes.map { it.toDto() },
            projects = projects.map { it.toDto() },
            tags = tags.map { it.toDto() },
            attachments = attachments.map { it.toDto() },
            taskTags = emptyList(), // TODO: implement task tag cross-refs
        )

        // 3. Serialize payload
        val payloadBytes = json.encodeToString(BackupPayload.serializer(), payload)
            .encodeToByteArray()

        // 4. Build manifest
        val counts = EntityCounts(
            tasks = tasks.size,
            notes = notes.size,
            projects = projects.size,
            tags = tags.size,
            attachments = attachments.size,
            taskTags = 0,
        )
        val manifest = BackupDomain.buildManifest(
            appVersion = options.appVersion,
            nowEpochMillis = now,
            userId = options.userId,
            payloadBytes = payloadBytes,
            counts = counts,
        )
        val manifestBytes = json.encodeToString(BackupManifest.serializer(), manifest)
            .encodeToByteArray()

        // 5. Read attachment files
        val attachmentEntries = mutableListOf<Pair<String, ByteArray>>()

        if (options.includeAttachments) {
            for (att in attachments) {
                val path = att.localPath
                if (path != null) {
                    attachmentEntries.add("${att.id}.dat" to fs.readBytes(path))
                }
            }
        }

        // 6. Write zip
        codec.export(manifestBytes, payloadBytes, attachmentEntries, options.destPath, fs)
            .getOrThrow()

        val byteSize = fs.readBytes(options.destPath).size.toLong()

        BackupResult(
            manifest = manifest,
            destPath = options.destPath,
            byteSize = byteSize,
        )
    }
}
