package com.singularity.todo.core.attachments.annotation

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Storage row for an annotation.
 *
 * ## No foreign key to `attachments.id`
 *
 * A soft-deleted attachment keeps its row, so a cascade would not fire on the delete that
 * actually happens — and a hard key would block restoring a file whose annotations still
 * exist, which is what a backup restore is. Ownership is enforced by the `user_id`
 * predicate on every query instead, the same way every other table in this database does
 * it.
 *
 * ## The synchronisation columns mirror `AttachmentEntity`
 *
 * `sync_status` / `server_version` / `hlc` are the same three columns an attachment
 * carries, and they are here for the same reason: an annotation follows its attachment, and
 * when attachment sync lands these rows ride in the same document. Until then they are
 * written with the default `Pending` and nothing reads them — see ADR
 * `2026-10-07-annotation-anchor-model`.
 */
@Entity(
    tableName = "attachment_annotations",
    indices = [
        Index("attachment_id"),
        Index("user_id"),
        Index("sync_status"),
    ],
)
data class AttachmentAnnotationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("attachment_id") val attachmentId: String,
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("range_start") val rangeStart: Int,
    @ColumnInfo("range_end") val rangeEnd: Int,
    /** Copy of the selected text — the anchor that survives an edit above the note. */
    val quote: String,
    val note: String = "",
    @ColumnInfo("sync_status") val syncStatus: String = "Pending", // AttachmentSyncStatus
    @ColumnInfo("created_at") val createdAt: Long, // epoch millis
    @ColumnInfo("updated_at") val updatedAt: Long, // epoch millis
    @ColumnInfo("deleted_at") val deletedAt: Long? = null, // epoch millis
    // Sync columns — mirror AttachmentEntity.
    @ColumnInfo("server_version") val serverVersion: Long = 0L,
    @ColumnInfo("hlc") val hlc: String? = null,
)
