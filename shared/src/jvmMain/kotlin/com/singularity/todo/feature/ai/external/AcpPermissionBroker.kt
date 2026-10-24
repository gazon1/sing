package com.singularity.todo.feature.ai.external

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

    private var pending: CompletableDeferred<PermissionDecision>? = null

    /**
     * Registers the request, so that an answer can reach it.
     *
     * The request is registered before the screen shows it: a reader who refuses the
     * moment it appears must not find that the request was not listening yet.
     */
    fun begin(): CompletableDeferred<PermissionDecision> {
        val decision = CompletableDeferred<PermissionDecision>()
        pending = decision
        return decision
    }

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
            if (pending === decision) pending = null
        }

    /** The reader answered. */
    fun answer(decision: PermissionDecision) {
        pending?.complete(decision)
    }

    /**
     * The request is refused: the turn was stopped, or the conversation ended.
     *
     * The refusal is allowed to reach the agent: a request that is answered and then
     * abandoned is a request the agent never got an answer to, and the agent is the
     * one that has to know.
     */
    fun decline() {
        pending?.complete(PermissionDecision.Declined)
        pending = null
    }

    companion object {
        /** How long a permission request waits before it declines itself. */
        val PERMISSION_TIMEOUT: Duration = 5.minutes
    }
}
