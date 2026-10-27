package com.singularity.todo.core.attachments.annotation

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Queries for [AttachmentAnnotationEntity].
 *
 * Every read and every mutation carries `user_id`. That is the whole isolation story for
 * this table: an annotation belongs to a profile, and a query that forgot the predicate
 * would show one profile another's notes.
 */
@Dao
interface AttachmentAnnotationDao {

    // ─── UserId-scoped reads ─────────────────────────────────────────────────────

    @Query(
        "SELECT * FROM attachment_annotations " +
            "WHERE attachment_id = :attachmentId AND user_id = :userId AND deleted_at IS NULL " +
            "ORDER BY created_at ASC",
    )
    fun watchByAttachmentForUser(attachmentId: String, userId: String): Flow<List<AttachmentAnnotationEntity>>

    @Query(
        "SELECT * FROM attachment_annotations WHERE id = :id AND user_id = :userId AND deleted_at IS NULL",
    )
    suspend fun getByIdForUser(id: String, userId: String): AttachmentAnnotationEntity?

    /** Backup export: every row for the profile, deleted ones included so a restore is lossless. */
    @Query("SELECT * FROM attachment_annotations WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<AttachmentAnnotationEntity>

    // ─── Writes ─────────────────────────────────────────────────────────────────

    @Upsert
    suspend fun upsert(entity: AttachmentAnnotationEntity)

    @Query(
        "UPDATE attachment_annotations SET note = :note, updated_at = :ts " +
            "WHERE id = :id AND user_id = :userId AND deleted_at IS NULL",
    )
    suspend fun updateNoteForUser(id: String, note: String, ts: Long, userId: String): Int

    /**
     * Soft delete — the row stays, so a sync that has not run yet cannot bring it back.
     *
     * Note that the range is deliberately not touched: editing a note and re-anchoring it
     * are different operations, and a note edit that silently moved offsets would re-point
     * the user's words at text they never selected.
     */
    @Query(
        "UPDATE attachment_annotations SET deleted_at = :ts, updated_at = :ts " +
            "WHERE id = :id AND user_id = :userId AND deleted_at IS NULL",
    )
    suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int

    /**
     * Hard-deletes every annotation of [userId] — soft-deleted rows included.
     *
     * An annotation is soft-deleted only; there is no hard delete on the single-row
     * path, so a sweep that follows live rows alone would leave the trashed ones.
     */
    @Query("DELETE FROM attachment_annotations WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String): Int

    /**
     * Hard-deletes one annotation row of [userId].
     *
     * The single-row counterpart of [deleteAllForUser]: a change in a backup that
     * says an annotation is gone says **which** one, and a statement that cannot
     * be aimed at one row would take the rest of the profile with it.
     *
     * @return the number of rows removed.
     */
    @Query("DELETE FROM attachment_annotations WHERE id = :id AND user_id = :userId")
    suspend fun deleteForUser(id: String, userId: String): Int
}
