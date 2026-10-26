package com.singularity.todo.feature.ai.external

import com.singularity.todo.feature.ai.acp.AcpPermissionRequest
import com.singularity.todo.feature.ai.acp.PermissionDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The permission handshake: the agent asks, the reader answers, and nobody answering
 * is an answer of its own.
 *
 * ## Why it is its own thing
 *
 * A permission request is a question with a deadline, and the deadline is the point:
 * an unanswered request is not a granted one, and a request nobody is looking at must
 * not hold a tool call open forever. The rules — wait, then decline; a request that
 * was outstanding when the turn was stopped is refused — are rules about the
 * *request*, and they had no home of their own: they lived between the turn that
 * asked and the screen that answered.
 */
internal class AcpPermissionBroker(private val timeout: Duration = PERMISSION_TIMEOUT) {

    /**
     * The requests that are waiting for an answer, oldest first.
     *
     * A queue rather than one slot: an agent may ask twice before the reader has answered
     * the first question, and a slot would drop the first request on the floor — its caller
     * would wait out the whole timeout for an answer the reader was never shown.
     */
    private val pending = ArrayDeque<PendingRequest>()

    private data class PendingRequest(
        val request: AcpPermissionRequest,
        val decision: CompletableDeferred<PermissionDecision>,
    )

    /**
     * Registers the request, so that an answer can reach it.
     *
     * The request is registered before the screen shows it: a reader who refuses the
     * moment it appears must not find that the request was not listening yet.
     */
    fun begin(request: AcpPermissionRequest): CompletableDeferred<PermissionDecision> {
        val decision = CompletableDeferred<PermissionDecision>()
        pending.addLast(PendingRequest(request, decision))
        return decision
    }

    /**
     * The request the reader is being asked about: the oldest one still waiting.
     *
     * The oldest, because it was asked first and the reader is owed that answer before
     * the next one.
     */
    fun onScreen(): AcpPermissionRequest? = pending.firstOrNull()?.request

    /**
     * Waits for the reader, and declines the request if nobody answers in time.
     *
     * An unanswered request is not a granted one, so the timeout answers "no" rather
     * than answering nothing.
     */
    suspend fun await(decision: CompletableDeferred<PermissionDecision>): PermissionDecision =
        try {
            withTimeoutOrNull(timeout) { decision.await() } ?: PermissionDecision.Declined
        } finally {
            pending.removeAll { it.decision === decision }
        }

    /**
     * The reader answered the request on screen.
     *
     * The oldest waiting request is the one on screen, so that is the one answered —
     * and it leaves the screen **now**, not when the coroutine waiting on it wakes up.
     * A request that stayed on the screen until its waiter resumed would keep the
     * reader looking at a question they have already answered, and the next question
     * would wait for a wake-up that has nothing to do with the reader.
     */
    fun answer(decision: PermissionDecision) {
        val answered = pending.removeFirstOrNull() ?: return
        answered.decision.complete(decision)
    }

    /**
     * Every outstanding request is refused: the turn was stopped, or the conversation ended.
     *
     * The refusal is allowed to reach the agent: a request that is answered and then
     * abandoned is a request the agent never got an answer to, and the agent is the
     * one that has to know.
     */
    fun decline() {
        pending.forEach { it.decision.complete(PermissionDecision.Declined) }
        pending.clear()
    }

    companion object {
        /** How long a permission request waits before it declines itself. */
        val PERMISSION_TIMEOUT: Duration = 5.minutes
    }
}
