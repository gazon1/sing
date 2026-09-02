package com.singularity.todo.core.attachments

import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.UserId
import kotlinx.datetime.Instant

data class Attachment(
    val id: AttachmentId,
    val taskId: TaskId,
    val userId: UserId,
    val type: AttachmentType,
    val url: String? = null,
    val title: String = "",
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val fileSizeBytes: Long = 0L,
    val mimeType: String? = null,
    val checksum: String? = null,
    val syncStatus: AttachmentSyncStatus = AttachmentSyncStatus.Pending,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    // Sync columns
    val serverVersion: Long = 0L,
    val hlc: String? = null
) {
    val isImage: Boolean get() =
        type == AttachmentType.Image || mimeType?.startsWith("image/") == true

    val isUrl: Boolean get() = type == AttachmentType.Url

    val isFile: Boolean get() = type == AttachmentType.File

    val hasLocal: Boolean get() = localPath != null

    val hasRemote: Boolean get() = remoteUrl != null

    val displayTitle: String get() = title.ifBlank {
        when {
            isUrl -> url?.takeLast(50) ?: "Link"
            localPath != null -> localPath.substringAfterLast('/').substringAfterLast('\\')
            else -> "Attachment"
        }
    }
}
