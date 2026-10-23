package com.singularity.todo.feature.proposals.presentation

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.EditProposalItemUseCase

/**
 * The five operations every proposal surface performs.
 *
 * ## Why this exists
 *
 * A proposal is answered in three places — the review screen, the note editor and
 * the task detail — and each of them used to carry its own copy of the same five
 * operations. Three copies drift: the task detail grew a "apply every change"
 * that reported a partly failed batch where the other two stayed silent, and the
 * reason a change failed reached the user in three different wordings. This class
 * is the one copy.
 *
 * ## What it does not know
 *
 * It does not know which surface it serves. The surface hands it three things:
 * the [ProposalFailureStore] that keeps the reasons next to the changes, the
 * [ProposalErrorSink] that tells the user about a failure, and the
 * [ProposalDecisionStyle] that says how this surface words it.
 *
 * ## The rules it keeps
 *
 * - A batch where some changes failed is not an error. Each failed change keeps
 *   its own reason next to it, and each change that worked has its reason
 *   cleared.
 * - A failure is reported where it happened: next to the change it belongs to.
 *
 * @param applyProposalItem Applies or turns down one change. Null when the
 *   surface has no write path for proposals — the task detail's collaboration
 *   dependencies are optional, and a surface without them has nothing to decide.
 * @param editProposalItem Changes the value of one change. Null when the surface
 *   cannot change a value.
 * @param style How this surface words a decision.
 * @param failures Where the reason a change failed lives.
 * @param errors Where a failure goes.
 * @param userId The user the decisions are made for.
 * @param withdrawProposal Withdraws a proposal, and its waiting changes go with
 *   it. The task detail's way of turning a whole proposal down; the review
 *   screen and the note editor turn every waiting change down on its own
 *   instead.
 */
class ProposalDecisions(
    private val applyProposalItem: ApplyProposalItemUseCase?,
    private val editProposalItem: EditProposalItemUseCase?,
    private val style: ProposalDecisionStyle,
    private val failures: ProposalFailureStore,
    private val errors: ProposalErrorSink,
    private val userId: () -> UserId,
    private val withdrawProposal: (suspend (ProposalId, UserId) -> Result<Unit>)? = null,
) {

    /**
     * Applies one change.
     *
     * The user turning the change down goes through [turnDownChange], which
     * carries the reason the user typed.
     */
    suspend fun applyChange(itemId: ProposalItemId) {
        val apply = applyProposalItem ?: return
        decide(itemId, style.apply) { apply.confirm(itemId, userId()) }
    }

    /**
     * Turns one change down.
     *
     * @param reason why the user turned it down — if they typed one. A surface
     *   that does not store the reason drops it here, exactly where it dropped
     *   it before.
     */
    suspend fun turnDownChange(itemId: ProposalItemId, reason: String?) {
        val apply = applyProposalItem ?: return
        val stored = if (style.storesRejectionReason) reason else null
        decide(itemId, style.reject) { apply.reject(itemId, userId(), stored) }
    }

    /**
     * Changes the value of one change, before it is applied.
     *
     * The value the user typed is read by the same reader that read the model's
     * value, so a value that does not read is refused — and the refusal carries
     * the reason, which is what the user is shown under the change.
     */
    suspend fun changeValue(itemId: ProposalItemId, text: String) {
        val edit = editProposalItem ?: return
        decide(itemId, style.change) { edit.edit(itemId, text, userId()) }
    }

    /**
     * Applies every waiting change of one proposal.
     *
     * A batch where some of the changes failed is not a crash: the reason of
     * each failed change sits next to that change. Reporting the batch as one
     * failure would report a normal outcome as an error.
     */
    suspend fun applyAll(proposalId: ProposalId) {
        val apply = applyProposalItem ?: return
        val label = style.applyAll
        when (val outcome = outcome { apply.confirmAll(proposalId, userId()) }) {
            is Outcome.Value -> keepOutcomeOfBatch(outcome.value)
            is Outcome.Thrown -> report(label, outcome.error)
        }
    }

    /**
     * Turns every waiting change of a proposal down, one by one.
     *
     * @param waiting the changes that are still waiting. The surface reads them
     *   the way it reads them everywhere else — the repository, or the state it
     *   already holds — and hands them over, so the read happens where it
     *   always happened.
     */
    suspend fun dismiss(waiting: suspend () -> List<ProposalItem>) {
        val apply = applyProposalItem ?: return
        val label = style.dismiss
        val outcome = outcome {
            for (item in waiting()) {
                turnDownWaiting(item)
            }
        }
        when (outcome) {
            is Outcome.Value -> Unit
            is Outcome.Thrown -> report(label, outcome.error)
        }
    }

    /**
     * Withdraws the proposal: its waiting changes go with it.
     *
     * The task detail's way of turning a whole proposal down. The review screen
     * and the note editor turn every waiting change down on its own instead,
     * through [dismiss].
     */
    suspend fun withdraw(proposalId: ProposalId) {
        val withdrawOperation = withdrawProposal ?: return
        val label = style.dismiss
        when (val outcome = outcome { withdrawOperation(proposalId, userId()) }) {
            is Outcome.Value -> outcome.value.exceptionOrNull()?.let { error -> report(label, error) }
            is Outcome.Thrown -> report(label, outcome.error)
        }
    }

    /** Keeps the reason a change failed next to the change it belongs to. */
    fun recordFailure(itemId: ProposalItemId, message: String) {
        failures.record(itemId, message)
    }

    /** Forgets the reason a change failed, once the change worked. */
    fun clearFailure(itemId: ProposalItemId) {
        failures.clear(itemId)
    }

    /**
     * Runs one decision and keeps its outcome: a change that failed keeps the
     * reason next to it, and a change that worked has its reason cleared.
     */
    private suspend fun decide(
        itemId: ProposalItemId,
        label: String,
        work: suspend () -> Result<*>,
    ) {
        when (val outcome = outcome(work)) {
            is Outcome.Value -> outcome.value.fold(
                onSuccess = { clearFailure(itemId) },
                onFailure = { error -> fail(itemId, label, error) },
            )

            is Outcome.Thrown -> report(label, outcome.error)
        }
    }

    /**
     * Keeps the outcome of a batch: each failed change keeps its own reason
     * next to it, and each change that worked has its reason cleared.
     */
    private suspend fun keepOutcomeOfBatch(batch: ApplyProposalItemUseCase.BatchResult) {
        batch.failed.forEach { failed ->
            recordFailure(failed.itemId, style.failureText.text(style.applyOne, failed.error))
        }
        batch.applied.forEach { applied -> clearFailure(applied.id) }
        val text = style.batchFailureText ?: return
        val first = batch.failed.firstOrNull() ?: return
        errors.report(style.applyAll, text(batch.failed.size), first.error)
    }

    /**
     * Turns one waiting change down as part of a whole proposal.
     *
     * A change that could not be turned down keeps its reason next to it; the
     * ones that were turned down have nothing left to explain, so their reason
     * is cleared. A surface that keeps no reason for a dismissed change keeps
     * neither.
     */
    private suspend fun turnDownWaiting(item: ProposalItem) {
        val apply = applyProposalItem ?: return
        apply.reject(item.id, userId(), null).fold(
            onSuccess = { if (style.dismissOne != null) clearFailure(item.id) },
            onFailure = { error ->
                val label = style.dismissOne ?: return@fold
                recordFailure(item.id, style.failureText.text(label, error))
            },
        )
    }

    /**
     * Keeps the reason a change failed next to the change it belongs to, and
     * tells the user about it.
     */
    private suspend fun fail(itemId: ProposalItemId, label: String, error: Throwable) {
        recordFailure(itemId, style.failureText.text(label, error))
        report(label, error)
    }

    /**
     * Reports a failure to the user, in the surface's own words.
     */
    private suspend fun report(label: String, error: Throwable) {
        errors.report(label, style.failureText.text(label, error), error)
    }

    /**
     * Runs one piece of work and says how it ended.
     *
     * A failure that arrives as a thrown exception and one that arrives as a
     * failed [Result] both end here, and both are handed to the surface: a
     * surface that shows a change shows the failure of that change too, and the
     * only question left is where it is shown — which is the surface's own
     * [ProposalErrorSink].
     *
     * A cancellation is not caught here: `runCatchingCancellable` re-throws it,
     * and a cancelled operation has to end the coroutine rather than become a
     * message.
     */
    private suspend fun <T> outcome(work: suspend () -> T): Outcome<T> =
        runCatchingCancellable { work() }.fold(
            onSuccess = { Outcome.Value(it) },
            onFailure = { Outcome.Thrown(it) },
        )

    /**
     * What one piece of work ended with.
     *
     * @property Value The work returned a value.
     * @property Thrown The work threw.
     */
    private sealed interface Outcome<out T> {
        data class Value<out T>(val value: T) : Outcome<T>
        data class Thrown(val error: Throwable) : Outcome<Nothing>
    }
}
