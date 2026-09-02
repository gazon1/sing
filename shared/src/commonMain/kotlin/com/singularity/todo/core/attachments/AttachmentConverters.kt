package com.singularity.todo.core.attachments

import androidx.room.TypeConverter

class AttachmentConverters {
    @TypeConverter
    fun fromAttachmentType(type: AttachmentType): String = type.name

    @TypeConverter
    fun toAttachmentType(value: String): AttachmentType = AttachmentType.fromString(value)

    @TypeConverter
    fun fromSyncStatus(status: AttachmentSyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): AttachmentSyncStatus = AttachmentSyncStatus.fromString(value)
}
