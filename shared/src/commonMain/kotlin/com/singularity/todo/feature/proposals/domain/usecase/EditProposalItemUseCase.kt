package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ReadResult
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository

/**
 * Changes the value of a proposal before it is applied.
 *
 * ## Why the user may change it
 *
 * The model named a value. The model may have named it wrong, and the user is the
 * one who knows what they meant. A proposal the user can only accept or refuse is a
 * proposal where the only way to get the right value is to start the conversation
 * again — which is the expensive way to fix a typo.
 *
 * ## Why the value is read, not stored as typed
 *
 * The typed dictionary reads a value through one reader, and this use case reads the
 * user's value through the same one. So a value that does not read is refused with
 * the same reason the model's value would have been refused with — and the refusal
 * carries the reason, so the user knows what to type instead.
 *
 * ## What it does not do
 *
 * It does not apply the change. The change is still a proposal until it is confirmed,
 * and a proposal that was edited is a proposal the user still has to agree to.
 */
class EditProposalItemUseCase(private val proposals: ProposalRepository) {

    /**
     * Replaces the value of [itemId] with what [newText] reads as.
     *
     * @return the item in its changed state.
     * @throws IllegalStateException when the item is gone, already decided, carries no
     *   value a person would type, or the value the user typed does not read — the
     *   message is the reason the user is shown.
     */
    suspend fun edit(itemId: ProposalItemId, newText: String, userId: UserId): Result<ProposalItem> =
        runCatchingCancellable {
            val stored = proposals.getItem(itemId) ?: error("The proposal is no longer there.")
            check(stored.status == ProposalItemStatus.Pending) {
                stored.statusFailure ?: "This change was already ${stored.status.name.lowercase()}."
            }
            val editable = stored.kind.editableProposal()
                ?: error("This change has no value you can change.")
            val kind = when (val changed = editable.change(newText.trim())) {
                is ReadResult.Ok -> changed.value
                is ReadResult.Rejected -> error(changed.reason)
            }
            proposals.updatePendingItem(itemId, kind, userId).getOrThrow()
                ?: error("This change was decided before it could be changed.")
        }
}
