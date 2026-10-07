package com.singularity.todo.core.attachments

import com.singularity.todo.core.ids.nextId
import kotlinx.serialization.Serializable

/**
 * Identifier for an attachment: the `att_` prefix plus a ULID.
 *
 * ## Why a ULID and not a UUID
 *
 * It was `att_${UUID.randomUUID()}` — a random v4 string, and `java.util.UUID` does
 * not exist off the JVM. The project's own [nextId] is already a dependency of half
 * the codebase, so this is now the same identifier shape as a task, a note and a
 * project, and it sorts by creation time.
 *
 * Nothing parses the body of the id: it is opaque, and the length is not part of any
 * contract. Attachments created before this change keep their `att_<uuid>` form, which
 * is exactly why the format is not parsed anywhere.
 *
 * `@Serializable` because the attachment viewer is a navigation route
 * ([com.singularity.todo.feature.nav.AttachmentRoute.View]) and the Nav3 back stack
 * serializes its keys. Every other identifier here is annotated for the same reason —
 * `TaskId` and `NoteId` both are — so this is the house convention rather than a new
 * requirement. A value class that cannot be serialized cannot be a route parameter.
 */
@Serializable
@JvmInline
value class AttachmentId(val value: String) {
    companion object {
        fun generate() = AttachmentId("att_${nextId()}")
        fun fromString(value: String) = AttachmentId(value)
    }
}
