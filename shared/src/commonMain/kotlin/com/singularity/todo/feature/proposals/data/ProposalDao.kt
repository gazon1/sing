package com.singularity.todo.feature.proposals.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import com.singularity.todo.core.database.SyncColumns
import kotlinx.coroutines.flow.Flow

/**
 * A batch of AI-proposed changes awaiting confirmation.
 *
 * @param source Which surface produced the proposal.
 * @param status Aggregate status, derived from the items by
 *   [com.singularity.todo.feature.proposals.domain.model.ProposalStatusReducer].
 *   The column is a cache of that answer: it is written, and the domain derives the
 *   status from the items instead of reading it back.
 */
@Entity(
    tableName = "ai_proposal",
    indices = [
        Index(value = ["user_id", "target_kind", "target_id"]),
        Index("user_id"),
    ],
)
data class AiProposalEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("source") val source: String,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("target_kind", defaultValue = "TASK") val targetKind: String,
    @ColumnInfo("target_id") val targetId: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @Embedded val sync: SyncColumns = SyncColumns(),
)

/**
 * A single proposed change, individually confirmable.
 *
 * The unique index on `(proposal_id, fingerprint)` is the last line of defence
 * against a duplicate item: even if the same change were assembled twice within one
 * proposal, the second insert is rejected by SQLite rather than shown to the user
 * as a duplicate row.
 */
@Entity(
    tableName = "ai_proposal_item",
    foreignKeys = [
        ForeignKey(
            entity = AiProposalEntity::class,
            parentColumns = ["id"],
            childColumns = ["proposal_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("proposal_id"),
        Index("status"),
        Index(value = ["proposal_id", "fingerprint"], unique = true),
    ],
)
data class ProposalItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("proposal_id") val proposalId: String,
    @ColumnInfo("kind_json") val kindJson: String,
    @ColumnInfo("target_id") val targetId: String,
    @ColumnInfo("human_summary") val humanSummary: String,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("fingerprint") val fingerprint: String,
    @ColumnInfo("sort_order") val sortOrder: Int,
    @ColumnInfo("decided_at") val decidedAt: Long? = null,
    @ColumnInfo("decided_actor") val decidedActor: String? = null,
    @ColumnInfo("rejection_reason") val rejectionReason: String? = null,
)

/**
 * DAO for AI proposals and their items.
 *
 * ## The claim
 *
 * A change is decided through [ProposalItemDao.claimItem], which moves an item
 * out of the status it carries with a compare-and-set. A double-tap, or a retry
 * after a crash, loses the race and gets 0 back — the caller then applies
 * nothing. Doing this as read-then-write instead would let both taps dispatch.
 *
 * ## The status column
 *
 * The column is a cache of what the changes say — see
 * [com.singularity.todo.feature.proposals.domain.model.ProposalStatusReducer] —
 * and it is written, never read into the domain. The questions that need the
 * truth ask the changes.
 */
@Dao
interface ProposalDao {

    // ── Proposals ─────────────────────────────────────────────────────────────

    @Upsert
    suspend fun upsertProposal(entity: AiProposalEntity)

    @Query("SELECT * FROM ai_proposal WHERE id = :id LIMIT 1")
    suspend fun getProposal(id: String): AiProposalEntity?

    @Query("SELECT * FROM ai_proposal WHERE id = :id LIMIT 1")
    fun watchProposal(id: String): Flow<AiProposalEntity?>

    @Query(
        """
        SELECT * FROM ai_proposal
        WHERE target_id = :targetId AND target_kind = :targetKind AND user_id = :userId
        ORDER BY created_at DESC
        """,
    )
    fun watchProposalsForTarget(targetId: String, targetKind: String, userId: String): Flow<List<AiProposalEntity>>

    /**
     * The proposals of one user that still wait for a decision, newest first.
     *
     * The question is asked of the *changes*, not of the row's own status column:
     * the column is a cache of what the changes say, and a cache that disagrees
     * with the changes would hide a proposal the user still has to decide about.
     *
     * The same rule the domain applies — a change that has not been decided keeps
     * the proposal waiting — is written here as SQL, so the rows that have to be
     * read are the ones that are waiting and not every proposal the user ever
     * made.
     */
    @Query(
        """
        SELECT p.* FROM ai_proposal p
        WHERE p.user_id = :userId
          AND EXISTS (
            SELECT 1 FROM ai_proposal_item i
            WHERE i.proposal_id = p.id
              AND i.status NOT IN ('Confirmed','Rejected','Retracted')
          )
        ORDER BY p.created_at DESC
        """,
    )
    fun watchWaitingProposals(userId: String): Flow<List<AiProposalEntity>>

    @Query(
        """
        UPDATE ai_proposal SET status = :status, updated_at = :updatedAt
        WHERE id = :id AND user_id = :userId
        """,
    )
    suspend fun updateProposalStatus(id: String, status: String, updatedAt: Long, userId: String): Int

    /**
     * Hard-deletes every proposal of [userId].
     *
     * The items of a proposal are deleted by [ProposalItemDao.deleteAllForUser]; the
     * foreign key cascades too, but the explicit delete keeps the order visible.
     */
    @Query("DELETE FROM ai_proposal WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String): Int
}
