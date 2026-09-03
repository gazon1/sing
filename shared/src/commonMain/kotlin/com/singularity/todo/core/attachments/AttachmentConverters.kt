package com.singularity.todo.core.attachments

import androidx.room3.ColumnTypeConverter

class AttachmentConverters {
    @ColumnTypeConverter
    fun fromAttachmentType(type: AttachmentType): String = type.name

    @ColumnTypeConverter
    fun toAttachmentType(value: String): AttachmentType = AttachmentType.fromString(value)

    @ColumnTypeConverter
    fun fromSyncStatus(status: AttachmentSyncStatus): String = status.name

    @ColumnTypeConverter
    fun toSyncStatus(value: String): AttachmentSyncStatus = AttachmentSyncStatus.fromString(value)
}
