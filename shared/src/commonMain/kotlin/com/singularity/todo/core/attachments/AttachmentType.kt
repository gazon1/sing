package com.singularity.todo.core.attachments

enum class AttachmentType {
    File,
    Url,
    Image;

    companion object {
        fun fromString(value: String): AttachmentType =
            entries.find { it.name == value } ?: File
    }
}
