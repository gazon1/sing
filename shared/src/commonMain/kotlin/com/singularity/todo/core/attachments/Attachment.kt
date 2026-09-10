package com.singularity.todo.core.attachments

import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.core.ids.UserId

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
    val createdAt: kotlin.time.Instant,
    val updatedAt: kotlin.time.Instant,
    val deletedAt: kotlin.time.Instant? = null,
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
