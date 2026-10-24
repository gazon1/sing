package com.singularity.todo.feature.ai.external

import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.feature.ai.acp.AcpAgentSession
import com.singularity.todo.feature.ai.acp.AcpTurnEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One turn of a conversation, and the rules about when it may write to the screen.
 *
 * ## Why it is its own thing
 *
 * A turn has a clock of its own: it is started, it produces activity while it runs,
 * it ends exactly once, and it can be stopped. The rules about *when* a turn may
 * write — a stopped turn that is still running must not write onto the next one —
 * are rules about the turn, and they were written in the screen's ViewModel, mixed in
 * with the state updates that show them.
 *
 * ## A turn ends one way
 *
 * The outcome is set exactly once per turn, and it is set from the protocol's
 * completion event. A stream that ends without one — the agent's process died, the
 * transport broke — is a **failure**, and the screen says so. Telling the reader the
 * agent finished when it did not is the one thing this screen must not do.
 *
 * ## A failed turn is not retried
 *
 * There is no retry anywhere in here. An agent that failed mid-turn failed for a
 * reason the user cannot see, and repeating the request would repeat the failure with
 * a second copy of whatever it had already done.
 */
internal class AcpTurnRunner(
    private val scope: CoroutineScope,
    private val onActivity: (assistantId: String, event: AgentEvent) -> Unit,
    private val onOutcome: (outcome: AgentTurnOutcome) -> Unit,
) {

    /**
     * How many turns have been started, and which one is the current one.
     *
     * A turn that has been stopped keeps running for a moment — the agent is asked to
     * stop, and the request is a request — and it can end after the next turn has
     * begun. Its outcome belongs to the turn it ended, not to the one on screen, so
     * every outcome carries the turn it came from and a turn that is no longer the
     * current one cannot write to the state.
     */
    private var turnNumber: Long = 0
    private var currentTurn: Long = 0
    private var job: Job? = null
    private var outcomeRecorded: Boolean = false

    /**
     * Starts a turn.
     *
     * @param assistantId the message the agent's text is written into.
     * @param run the turn's body: it is given a sink for the protocol's events and
     *   returns when the turn is over.
     */
    @Suppress("TooGenericExceptionCaught")
    fun start(assistantId: String, run: suspend (suspend (AcpTurnEvent) -> Unit) -> Unit) {
        val turnId = ++turnNumber
        currentTurn = turnId
        outcomeRecorded = false
        val previousTurn = job

        job = scope.launch {
            // A turn that is still running is ended before the next one starts. Two
            // prompts on one session are two conversations, and the protocol promises
            // nothing sensible about the second one; and the turn that is ending
            // belongs to the turn that started it.
            previousTurn?.cancelAndJoin()
            var outcome: AgentTurnOutcome? = null
            try {
                run { event ->
                    when (event) {
                        is AcpTurnEvent.Activity -> applyActivity(turnId, assistantId, event.event)
                        is AcpTurnEvent.Finished -> outcome = event.outcome
                    }
                }
            } catch (e: CancellationException) {
                // The same distinction as on the way in: a cancellation the screen did
                // not ask for is the agent's process dying mid-turn, and the turn has to
                // end as a failure. Reporting it as "cancelled" would tell the reader
                // that they stopped something that had already stopped.
                if (!currentCoroutineContext().isActive) throw e
                outcome = AgentTurnOutcome.Failed("The agent stopped without answering.")
            } catch (e: Exception) {
                outcome = AgentTurnOutcome.Failed(e.message ?: "The agent stopped unexpectedly.")
            }

            // The absence of a completion event is the failure. There is no path that
            // ends a turn without one and calls it a success.
            recordOutcome(turnId, outcome ?: AgentTurnOutcome.Failed("The agent stopped without answering."))
        }
    }

    /**
     * Records how the turn ended — once.
     *
     * A turn reaches exactly one terminal outcome. The reader is told "cancelled" and
     * then, a moment later, the agent's own completion arrives and says "completed":
     * two answers to one question, and the second one is the lie. So the outcome is
     * written only while it is still unset, and a late event cannot change how the
     * turn ended.
     *
     * The outcome also carries the turn it belongs to. A turn that was stopped keeps
     * running for a moment, and it can end after the next turn has begun; its outcome
     * is that turn's, and writing it onto the next one would tell the reader the
     * running turn finished when it has not.
     */
    fun recordOutcome(turnId: Long, outcome: AgentTurnOutcome) {
        if (turnId != currentTurn) return
        if (outcomeRecorded) return
        outcomeRecorded = true
        onOutcome(outcome)
    }

    /**
     * Records what the agent did — for the turn that is on screen.
     *
     * The activity carries the turn it belongs to, for the same reason the outcome
     * does: a turn that was stopped keeps running for a moment, and what it says on
     * its way out belongs to that turn, not to the one the reader is looking at.
     */
    private fun applyActivity(turnId: Long, assistantId: String, event: AgentEvent) {
        if (turnId != currentTurn) return
        onActivity(assistantId, event)
    }

    /**
     * Stops the turn that is running.
     *
     * The job of the turn being stopped is captured here. The field is reassigned by
     * the next turn, and the teardown has to wait for *this* turn: a teardown that
     * waited for the wrong one would either give up on a turn that is still running
     * or wait for one that has not started.
     */
    fun captureRunningTurn(): RunningTurn = RunningTurn(currentTurn, job)

    /**
     * Ends a turn that did not end when it was asked to.
     *
     * The turn's job is waited for, briefly. If it is still running afterwards, the
     * agent is torn down: a process that will not stop is not a turn the reader can
     * leave.
     *
     * @param turn the job of the turn that was stopped, captured when it was stopped.
     */
    suspend fun endTurnThatOutlivedTheRequest(session: AcpAgentSession, turn: Job?) {
        val running = turn ?: return
        val ended = withTimeoutOrNull(CANCEL_GRACE) { running.join() } != null
        if (!ended) {
            session.terminate()
        }
    }

    /** Stops the turn that is running, and forgets it. */
    fun close() {
        job?.cancel()
        job = null
    }

    /** The turn that is on screen, and the job that is running it. */
    data class RunningTurn(val turnId: Long, val job: Job?)

    companion object {
        /**
         * How long an agent is given to end a cancelled turn on its own.
         *
         * The protocol's cancellation is a request, and an agent is free to ignore it.
         * The manual pass found one that does: Claude Code's bridge answers
         * `session/cancel` with "method not found" and keeps working. So the screen
         * asks, waits a moment, and then ends the agent itself — a reader who pressed
         * cancel must not watch the turn continue.
         */
        val CANCEL_GRACE: Duration = 5.seconds
    }
}
