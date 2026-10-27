package com.singularity.todo.core.backup

import com.singularity.todo.core.attachments.AttachmentEntity
import com.singularity.todo.core.attachments.annotation.AttachmentAnnotationEntity
import kotlinx.serialization.Serializable

/**
 * The attachment rows of a payload: the attachments, and their annotations.
 */
// ─── AttachmentDto ─────────────────────────────────────────────────────────────

@Serializable
data class AttachmentDto(
    val id: String,
    val taskId: String,
    val type: String,
    val url: String? = null,
    val title: String = "",
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val fileSizeBytes: Long = 0L,
    val mimeType: String? = null,
    val checksum: String? = null,
    val syncStatus: String = "Pending",
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

fun AttachmentEntity.toDto(): AttachmentDto =
    AttachmentDto(
        id = id,
        taskId = taskId,
        type = type,
        url = url,
        title = title,
        localPath = localPath,
        remoteUrl = remoteUrl,
        fileSizeBytes = fileSizeBytes,
        mimeType = mimeType,
        checksum = checksum,
        syncStatus = syncStatus,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun AttachmentDto.toEntity(userId: String): AttachmentEntity =
    AttachmentEntity(
        id = id,
        taskId = taskId,
        userId = userId,
        type = type,
        url = url,
        title = title,
        localPath = localPath,
        remoteUrl = remoteUrl,
        fileSizeBytes = fileSizeBytes,
        mimeType = mimeType,
        checksum = checksum,
        syncStatus = syncStatus,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        serverVersion = 0L,
        hlc = null,
    )
// ─── AttachmentAnnotationDto ───────────────────────────────────────────────────
//
// The range travels as four flat fields rather than a nested object, for the same reason
// `AttachmentDto` is flat: a backup is read by whatever client restores it, possibly a
// different build, and a shape that grows a field can be decoded by a client that predates
// the field. `attachmentId` is the one reference an annotation cannot do without.

@Serializable
data class AttachmentAnnotationDto(
    val id: String,
    val attachmentId: String,
    val rangeStart: Int,
    val rangeEnd: Int,
    val quote: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

fun AttachmentAnnotationEntity.toDto(): AttachmentAnnotationDto =
    AttachmentAnnotationDto(
        id = id,
        attachmentId = attachmentId,
        rangeStart = rangeStart,
        rangeEnd = rangeEnd,
        quote = quote,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

/**
 * Rebuilds the row, stamping [userId] rather than carrying it.
 *
 * A restore targets `options.targetUserId`, which is not necessarily the profile that made
 * the backup — the DTO must not be able to say otherwise. Same rule as [AttachmentDto.toEntity].
 */
fun AttachmentAnnotationDto.toEntity(userId: String): AttachmentAnnotationEntity =
    AttachmentAnnotationEntity(
        id = id,
        attachmentId = attachmentId,
        userId = userId,
        rangeStart = rangeStart,
        rangeEnd = rangeEnd,
        quote = quote,
        note = note,
        syncStatus = "Pending",
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        serverVersion = 0L,
        hlc = null,
    )
