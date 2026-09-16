package com.singularity.todo.core.attachments

enum class AttachmentSyncStatus {
    Pending,
    Synced,
    Error,
    ;

    companion object {
        fun fromString(value: String): AttachmentSyncStatus = entries.find { it.name == value } ?: Pending
    }
}
