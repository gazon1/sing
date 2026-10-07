package com.singularity.todo.core.attachments.annotation

import com.singularity.todo.core.ids.UserId
import kotlin.time.Instant

/**
 * A note the user wrote against a span of a text attachment.
 *
 * Soft-deleted, like every other entity in this database: [deletedAt] is set and the row
 * stays, so a sync that has not yet run cannot resurrect a note the user threw away.
 *
 * @param userId the profile that owns the note. Stamped by the repository from the ambient
 *   scope, never from the caller — see
 *   [com.singularity.todo.core.attachments.annotation.AttachmentAnnotationRepository].
 */
data class AttachmentAnnotation(
    val id: AttachmentAnnotationId,
    val range: TextRange,
    val note: String,
    val userId: UserId,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    /** Shorthand for `range.attachmentId`, which is where the attachment lives anyway. */
    val attachmentId get() = range.attachmentId

    val isDeleted: Boolean get() = deletedAt != null

    /**
     * Resolves this annotation against the current text of its attachment.
     *
     * A convenience over [resolveAnchor] so a caller holding an annotation does not have to
     * reach past it to the range. The pure function stays the place the ordering is tested.
     */
    fun anchorIn(documentText: String): AnchorResolution = resolveAnchor(range, documentText)

    /**
     * Whether this note still points at the words it was written against.
     *
     * Derived rather than stored: a column would have to be rewritten every time the file
     * changes, and there is no writer standing where the file changes.
     */
    fun isStaleIn(documentText: String): Boolean =
        anchorIn(documentText) is AnchorResolution.Stale
}
