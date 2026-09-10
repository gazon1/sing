package com.singularity.todo.core.attachments

import com.singularity.todo.core.files.MimeTypes
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface AttachmentRepository {
    fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<Attachment>>
    suspend fun create(attachment: Attachment): Result<Unit>
    suspend fun delete(id: AttachmentId): Result<Unit>
    suspend fun saveFileAttachment(
        taskId: TaskId,
        userId: UserId,
        sourcePath: String,
        mimeType: String?
    ): Result<Attachment>
    suspend fun addUrlAttachment(
        taskId: TaskId,
        userId: UserId,
        url: String,
        title: String?
    ): Result<Attachment>
}

class AttachmentRepositoryImpl(
    private val dao: AttachmentDao,
    private val storage: AttachmentStorage,
    private val uploadService: AttachmentUploadService,
    private val clock: Clock
) : AttachmentRepository {

    override fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<Attachment>> =
        dao.watchByTask(taskId.value).map { entities ->
            entities.map { it.toAttachment() }
        }

    override suspend fun create(attachment: Attachment): Result<Unit> = runCatching {
        dao.upsert(attachment.toEntity())
    }

    override suspend fun delete(id: AttachmentId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        dao.softDelete(id.value, ts)
    }

    override suspend fun saveFileAttachment(
        taskId: TaskId,
        userId: UserId,
        sourcePath: String,
        mimeType: String?
    ): Result<Attachment> = runCatching {
        val id = AttachmentDomain.generateAttachmentId()
        val ext = AttachmentDomain.extractExtension(sourcePath)
        val detectedMime = mimeType ?: MimeTypes.fromExtension(ext)
        val now = clock.now()

        // Save to local storage
        val localPath = storage.saveFile(taskId.value, id.value, sourcePath, ext).getOrThrow()
        val checksum = storage.computeChecksum(localPath).getOrNull()

        val attachment = Attachment(
            id = id,
            taskId = taskId,
            userId = userId,
            type = if (MimeTypes.isImage(detectedMime)) AttachmentType.Image else AttachmentType.File,
            localPath = localPath,
            fileSizeBytes = 0L,
            mimeType = detectedMime,
            checksum = checksum,
            syncStatus = AttachmentSyncStatus.Pending,
            createdAt = now,
            updatedAt = now
        )

        dao.upsert(attachment.toEntity())
        attachment
    }

    override suspend fun addUrlAttachment(
        taskId: TaskId,
        userId: UserId,
        url: String,
        title: String?
    ): Result<Attachment> = runCatching {
        AttachmentDomain.validateUrl(url).getOrThrow()

        val id = AttachmentDomain.generateAttachmentId()
        val now = clock.now()

        val attachment = Attachment(
            id = id,
            taskId = taskId,
            userId = userId,
            type = AttachmentType.Url,
            url = url,
            title = title ?: "",
            syncStatus = AttachmentSyncStatus.Pending,
            createdAt = now,
            updatedAt = now
        )

        dao.upsert(attachment.toEntity())
        attachment
    }
}

// --- Mappers ---

private fun AttachmentEntity.toAttachment(): Attachment = Attachment(
    id = AttachmentId.fromString(id),
    taskId = TaskId.fromString(taskId),
    userId = UserId.fromString(userId),
    type = AttachmentType.fromString(type),
    url = url,
    title = title,
    localPath = localPath,
    remoteUrl = remoteUrl,
    fileSizeBytes = fileSizeBytes,
    mimeType = mimeType,
    checksum = checksum,
    syncStatus = AttachmentSyncStatus.fromString(syncStatus),
    createdAt = kotlin.time.Instant.fromEpochMilliseconds(createdAt),
    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(updatedAt),
    deletedAt = deletedAt?.let { kotlin.time.Instant.fromEpochMilliseconds(it) }
)

private fun Attachment.toEntity(): AttachmentEntity = AttachmentEntity(
    id = id.value,
    taskId = taskId.value,
    userId = userId.value,
    type = type.name,
    url = url,
    title = title,
    localPath = localPath,
    remoteUrl = remoteUrl,
    fileSizeBytes = fileSizeBytes,
    mimeType = mimeType,
    checksum = checksum,
    syncStatus = syncStatus.name,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    deletedAt = deletedAt?.toEpochMilliseconds(),
    serverVersion = serverVersion,
    hlc = hlc
)
