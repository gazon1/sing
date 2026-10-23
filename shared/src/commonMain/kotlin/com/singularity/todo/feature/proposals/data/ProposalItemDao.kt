package com.singularity.todo.feature.proposals.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the individually-decidable items of a proposal.
 *
 * Split from [ProposalDao] because a proposal and its items are separate aggregates:
 * the batch has an identity and a lifecycle of its own, and folding both into one
 * interface made every fake of it fifteen methods wide.
 *
 * ## Ownership
 *
 * `ai_proposal_item` carries no `user_id` column of its own — ownership lives on the
 * parent proposal, and duplicating it here would be a second source of truth that
 * could drift. Every write therefore scopes through a subquery on the parent, which
 * is what keeps a decision from being applied to another profile's item.
 *
 * ## The claim
 *
 * [claimItem] is the only path that moves an item out of `pending`, and it does so
 * with a compare-and-set (`WHERE status = 'Pending'`). A double-tap, or a retry after
 * a crash, loses the race and gets 0 back — the caller then applies nothing. Doing
 * this as read-then-write instead would let both taps dispatch.
 */
@Dao
interface ProposalItemDao {

    // ── Items ─────────────────────────────────────────────────────────────────

    @Upsert
    suspend fun upsertItem(entity: ProposalItemEntity)

    @Query("SELECT * FROM ai_proposal_item WHERE id = :id LIMIT 1")
    suspend fun getItem(id: String): ProposalItemEntity?

    @Query("SELECT * FROM ai_proposal_item WHERE proposal_id = :proposalId ORDER BY sort_order ASC")
    fun watchItemsForProposal(proposalId: String): Flow<List<ProposalItemEntity>>

    @Query("SELECT * FROM ai_proposal_item WHERE proposal_id = :proposalId ORDER BY sort_order ASC")
    suspend fun getItemsForProposal(proposalId: String): List<ProposalItemEntity>

    @Query("SELECT COUNT(*) FROM ai_proposal_item WHERE proposal_id = :proposalId AND fingerprint = :fingerprint")
    suspend fun countItemsWithFingerprint(proposalId: String, fingerprint: String): Int

    /**
     * The compare-and-set that makes confirm/reject exactly-once.
     *
     * @return 1 if this call won the race, 0 if the item was already decided.
     */
    @Query(
        """
        UPDATE ai_proposal_item
        SET status = :status,
            decided_at = :decidedAt,
            decided_actor = :actor,
            rejection_reason = :reason
        WHERE id = :id
          AND proposal_id IN (SELECT id FROM ai_proposal WHERE user_id = :userId)
          AND status = 'Pending'
        """,
    )
    suspend fun claimItem(
        userId: String,
        id: String,
        status: String,
        decidedAt: Long,
        actor: String,
        reason: String?,
    ): Int

    @Query(
        """
        UPDATE ai_proposal_item SET status = 'Retracted'
        WHERE proposal_id = :proposalId
          AND proposal_id IN (SELECT id FROM ai_proposal WHERE user_id = :userId)
          AND status = 'Pending'
        """,
    )
    suspend fun retractPendingItems(proposalId: String, userId: String): Int

    /**
     * Puts a claimed item back into `Pending`.
     *
     * The claim is the decision; the dispatch is the work. When the work fails, the
     * decision must not stand on its own: nothing was changed, so the item goes back
     * to `Pending` and the user can try again — with the reason next to it.
     *
     * The condition is the mirror of the claim's: only an item that this user's claim
     * confirmed, and that nothing else has decided since, can be released.
     *
     * @return 1 if the item was released, 0 if it was not confirmed by this user.
     */
    @Query(
        """
        UPDATE ai_proposal_item
        SET status = 'Pending',
            decided_at = NULL,
            decided_actor = NULL,
            rejection_reason = NULL
        WHERE id = :id
          AND proposal_id IN (SELECT id FROM ai_proposal WHERE user_id = :userId)
          AND status = 'Confirmed'
          AND decided_actor = :actor
        """,
    )
    suspend fun releaseClaim(userId: String, id: String, actor: String): Int

    /**
     * Rewrites what a pending item asks for.
     *
     * The user may change the value of a proposal before it is applied, and the
     * change has to be stored — with the summary the user reads and the fingerprint
     * the rejection guard compares, because both belong to the value.
     *
     * The `status = 'Pending'` condition is the same guard the claim uses: a decided
     * item is a decision, and rewriting it would rewrite a decision the user already
     * made.
     *
     * @return 1 if the item was rewritten, 0 if it was not pending or not the user's.
     */
    @Query(
        """
        UPDATE ai_proposal_item
        SET kind_json = :kindJson,
            human_summary = :humanSummary,
            fingerprint = :fingerprint
        WHERE id = :id
          AND proposal_id IN (SELECT id FROM ai_proposal WHERE user_id = :userId)
          AND status = 'Pending'
        """,
    )
    suspend fun updatePendingItem(
        userId: String,
        id: String,
        kindJson: String,
        humanSummary: String,
        fingerprint: String,
    ): Int

    /**
     * Fingerprints the user has rejected, most recent first.
     *
     * Joined through the proposal rather than stored denormalized on the item,
     * because `user_id` lives on the proposal and duplicating it would be a second
     * source of truth for ownership.
     */
    @Query(
        """
        SELECT i.fingerprint FROM ai_proposal_item i
        JOIN ai_proposal p ON p.id = i.proposal_id
        WHERE p.user_id = :userId AND i.status = 'Rejected'
        ORDER BY i.decided_at DESC
        LIMIT :limit
        """,
    )
    suspend fun rejectedFingerprints(userId: String, limit: Int): List<String>

    /**
     * Claims an item and reads it back in one transaction.
     *
     * The re-read matters: the claim is the moment the decision is final, so the row
     * the caller then dispatches against is the one the claim saw — not a separate
     * earlier read that a concurrent write could have invalidated.
     */
    @Transaction
    suspend fun claimAndRead(
        id: String,
        userId: String,
        status: String,
        decidedAt: Long,
        actor: String,
        reason: String?,
    ): ProposalItemEntity? {
        val claimed = claimItem(userId, id, status, decidedAt, actor, reason)
        return if (claimed > 0) getItem(id) else null
    }

    /**
     * Hard-deletes every item of [userId]'s proposals.
     *
     * `ai_proposal_item` has no `user_id` of its own — ownership lives on the parent
     * proposal — so the scope goes through a subquery, exactly as every other write
     * on this table does.
     */
    @Query(
        """
        DELETE FROM ai_proposal_item
        WHERE proposal_id IN (SELECT id FROM ai_proposal WHERE user_id = :userId)
        """,
    )
    suspend fun deleteAllForUser(userId: String): Int
}
