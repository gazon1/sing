package com.singularity.todo.core.attachments.annotation

import com.singularity.todo.core.ids.nextId
import kotlinx.serialization.Serializable

/**
 * Identifier for an annotation: the `ann_` prefix plus a ULID.
 *
 * ## Why the same shape as [com.singularity.todo.core.attachments.AttachmentId]
 *
 * An annotation is a document-row like any other — it is backed by a table, exported in a
 * backup and eventually synced. Giving it the project's own [nextId] means it sorts by
 * creation time and looks like every other id in the database, so a row read out of a
 * backup and a row created on the device are indistinguishable to everything downstream.
 *
 * ## Why a distinct prefix from `att_`
 *
 * The two ids travel together — an annotation names the attachment it annotates, and both
 * appear in a backup payload — so a shared prefix would make a truncated archive read as
 * valid. `ann_` cannot collide with `att_`, `tsk_` or any other entity prefix, and nothing
 * parses the body of the id.
 *
 * `@Serializable` because the annotation list is part of the backup payload, which is a
 * `kotlinx.serialization` structure. [com.singularity.todo.core.attachments.AttachmentId]
 * is annotated for the same reason.
 */
@Serializable
@JvmInline
value class AttachmentAnnotationId(val value: String) {
    companion object {
        fun generate() = AttachmentAnnotationId("ann_${nextId()}")
        fun fromString(value: String) = AttachmentAnnotationId(value)
    }
}
