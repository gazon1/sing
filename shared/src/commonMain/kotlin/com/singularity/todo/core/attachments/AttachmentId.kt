package com.singularity.todo.core.attachments

import java.util.UUID

@JvmInline
value class AttachmentId(val value: String) {
    companion object {
        fun generate() = AttachmentId("att_${UUID.randomUUID()}")
        fun fromString(value: String) = AttachmentId(value)
    }
}
