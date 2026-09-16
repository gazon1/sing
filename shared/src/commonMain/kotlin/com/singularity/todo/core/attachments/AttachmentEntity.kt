package com.singularity.todo.core.attachments

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "attachments",
    indices = [
        Index("task_id"),
        Index("user_id"),
        Index("sync_status"),
    ],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("task_id") val taskId: String,
    @ColumnInfo("user_id") val userId: String,
    val type: String, // AttachmentType as String (TypeConverter handles enum↔String)
    @ColumnInfo("url") val url: String? = null,
    val title: String = "",
    @ColumnInfo("local_path") val localPath: String? = null,
    @ColumnInfo("remote_url") val remoteUrl: String? = null,
    @ColumnInfo("file_size_bytes") val fileSizeBytes: Long = 0L,
    @ColumnInfo("mime_type") val mimeType: String? = null,
    @ColumnInfo("checksum") val checksum: String? = null,
    @ColumnInfo("sync_status") val syncStatus: String = "Pending", // AttachmentSyncStatus
    @ColumnInfo("created_at") val createdAt: Long, // epoch millis
    @ColumnInfo("updated_at") val updatedAt: Long, // epoch millis
    @ColumnInfo("deleted_at") val deletedAt: Long? = null, // epoch millis
    // Sync columns
    @ColumnInfo("server_version") val serverVersion: Long = 0L,
    @ColumnInfo("hlc") val hlc: String? = null,
)
