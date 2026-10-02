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
 *   [com.singularity.todo.feature.proposals.domain.logic.ProposalStatusReducer].
 */
@Entity(
    tableName = "ai_proposal",
    indices = [
        Index("task_id"),
        Index("user_id"),
    ],
)
data class AiProposalEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("task_id") val taskId: String,
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("source") val source: String,
    @ColumnInfo("status") val status: String,
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
 * [claimItem] is the only path that moves an item out of `pending`, and it does so
 * with a compare-and-set (`WHERE status = 'pending'`). A double-tap, or a retry after
 * a crash, loses the race and gets `false` back — the caller then applies nothing.
 * Doing this as read-then-write instead would let both taps dispatch.
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
        WHERE task_id = :taskId AND user_id = :userId
        ORDER BY created_at DESC
        """,
    )
    fun watchProposalsForTask(taskId: String, userId: String): Flow<List<AiProposalEntity>>

    @Query(
        """
        SELECT * FROM ai_proposal
        WHERE user_id = :userId AND status = :status
        ORDER BY created_at DESC
        """,
    )
    fun watchProposalsByStatus(userId: String, status: String): Flow<List<AiProposalEntity>>

    @Query(
        """
        UPDATE ai_proposal SET status = :status, updated_at = :updatedAt
        WHERE id = :id AND user_id = :userId
        """,
    )
    suspend fun updateProposalStatus(id: String, status: String, updatedAt: Long, userId: String): Int
}
