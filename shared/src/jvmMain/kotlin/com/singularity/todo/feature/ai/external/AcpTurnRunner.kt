package com.singularity.todo.feature.ai.external

import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.feature.ai.acp.AcpAgentSession
import com.singularity.todo.feature.ai.acp.AcpTurnEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
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
    /**
     * The session the turn was talking to is gone.
     *
     * Reported once per turn, for the turn that is on screen, when the turn ends without the
     * protocol's completion event and the reader did not stop it. The screen answers it by
     * marking the connection failed — the reader is owed the reason and a way to connect
     * again.
     */
    private val onSessionLost: (reason: String) -> Unit,
    /**
     * Whether the turn is waiting on the reader right now.
     *
     * A permission request is the turn talking to the reader, not the agent going quiet. The
     * agent is silent because it is *asked* to be, and a wait longer than the idle window is
     * normal: the permission deadline is five minutes. So the idle window is re-armed while
     * this is true instead of ending a turn that is working as designed.
     */
    private val awaitingReader: () -> Boolean,
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
     * Starts a turn and runs it to exactly one ending.
     *
     * The turn is bounded by **inactivity**: no event for [TURN_IDLE_TIMEOUT] ends it, because
     * an agent that is working emits something — text, a tool call, a status — and an agent
     * that has stopped saying anything is not a turn the reader can wait for. A total budget
     * would be the wrong rule: it would kill a long but healthy turn.
     *
     * A turn that ends without the protocol's completion event, and that the reader did not
     * stop, means the session is gone: it is reported to [onSessionLost] once, for the turn on
     * screen.
     *
     * @param assistantId the message the agent's text is written into.
     * @param events the turn's event stream, as the session produces it.
     */
    @Suppress("TooGenericExceptionCaught")
    fun start(assistantId: String, events: Flow<AcpTurnEvent>) {
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
            var completed: Boolean = false
            var outcome: AgentTurnOutcome? = null
            var failure: String? = null

            // The turn's events are drained into a channel by one producer, and consumed here
            // one at a time under a deadline. A flow is collected once; the deadline has to be
            // re-armed per event, and while the reader is being asked. The buffer is bounded:
            // it is the transport's backpressure, and a full channel can never starve the
            // deadline — a full buffer means the consumer has something to take.
            val received = Channel<AcpTurnEvent>(capacity = EVENT_BUFFER)
            val pump = launch {
                try {
                    events.collect { received.send(it) }
                    received.close()
                } catch (e: CancellationException) {
                    // Our own cancellation ends the turn quietly; one from the transport is the
                    // session dying, and its reason belongs to the reader.
                    if (currentCoroutineContext().isActive) received.close(e) else received.close()
                    throw e
                } catch (e: Exception) {
                    received.close(e)
                }
            }

            try {
                var step: Step
                do {
                    step = nextStep(received)
                    if (step is Step.Event) {
                        when (val event: AcpTurnEvent = step.event) {
                            is AcpTurnEvent.Activity -> applyActivity(turnId, assistantId, event.event)

                            is AcpTurnEvent.Finished -> {
                                completed = true
                                outcome = event.outcome
                            }
                        }
                    }
                    // A completion ends the turn: waiting for more would keep the spinner up for
                    // a whole idle window after the agent had answered.
                } while (step is Step.Waiting || (step is Step.Event && !completed))

                if (step is Step.Failed) failure = step.reason
            } catch (e: CancellationException) {
                // The same distinction as on the way in: a cancellation the screen did
                // not ask for is the agent's process dying mid-turn, and the turn has to
                // end as a failure. Reporting it as "cancelled" would tell the reader
                // that they stopped something that had already stopped.
                if (!currentCoroutineContext().isActive) throw e
                failure = "The agent stopped without answering."
            } catch (e: Exception) {
                failure = e.message ?: "The agent stopped unexpectedly."
            } finally {
                pump.cancel()
            }

            // Whether the reader stopped this turn. A stopped turn ends here too, but its
            // ending says nothing about the session: the reader asked for it. The outcome
            // was already recorded by the cancel path, and that is the flag.
            val stoppedByReader: Boolean = outcomeRecorded
            val reason: String = failure ?: "The agent stopped without answering."

            // The absence of a completion event is the failure. There is no path that
            // ends a turn without one and calls it a success.
            recordOutcome(turnId, outcome ?: AgentTurnOutcome.Failed(reason))

            // A turn that ended without a completion, and that the reader did not stop, is a
            // session that is no longer there. Said once, for the turn that is on screen.
            if (!completed && !stoppedByReader) {
                if (turnId == currentTurn) onSessionLost(reason)
            }
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

    /**
     * One step of a turn: the next event, or a reason there will not be one.
     *
     * A small vocabulary because the loop that consumes the turn has to answer three questions
     * differently — take the event, wait again for a reader the agent is waiting on, or stop
     * with a reason — and a `Boolean`/nullable pair would make the loop read worse than the
     * rule it enforces.
     */
    private sealed interface Step {
        /** The next event the agent produced. */
        data class Event(val event: AcpTurnEvent) : Step

        /** The window elapsed while the reader is being asked — the agent is not silent. */
        data object Waiting : Step

        /** The stream closed without a completion. */
        data object Ended : Step

        /** The window elapsed with no question outstanding, or the transport threw. */
        data class Failed(val reason: String) : Step
    }

    /**
     * Waits for the turn's next step under the idle deadline, and re-arms while the reader is
     * being asked.
     */
    private suspend fun nextStep(received: Channel<AcpTurnEvent>): Step {
        val result = withTimeoutOrNull(TURN_IDLE_TIMEOUT) { received.receiveCatching() }
        if (result == null) {
            // An event that arrived exactly as the window closed is still the agent speaking:
            // take it rather than calling a turn that just answered hung.
            val late = received.tryReceive()
            if (late.isSuccess) return Step.Event(late.getOrThrow())
            // Nothing for the whole window. If the turn is waiting on the reader, the agent is
            // silent by design — the window is re-armed rather than allowed to expire.
            return if (awaitingReader()) {
                Step.Waiting
            } else {
                Step.Failed("The agent did not respond for ${TURN_IDLE_TIMEOUT.inWholeSeconds} seconds.")
            }
        }
        if (result.isClosed) {
            // The stream ended, or the transport threw. Either way there is no completion;
            // the cause, when there is one, is the reason.
            val cause: Throwable? = result.exceptionOrNull()
            return if (cause == null) Step.Ended else Step.Failed(cause.message ?: "The agent stopped unexpectedly.")
        }
        return Step.Event(result.getOrThrow())
    }

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

        /**
         * How long a turn may go without producing an event before it is hung.
         *
         * Inactivity, not total duration: a working agent emits text, tool calls and status
         * as it goes, so silence this long means silence. The number is the one FR-040 names;
         * its cost — a single tool call quieter than the window would be read as hung — is
         * stated in ADR `2026-10-26-a-turn-that-hangs-has-ended`.
         */
        val TURN_IDLE_TIMEOUT: Duration = 60.seconds

        /**
         * How many of the turn's events may wait to be folded.
         *
         * Bounded on purpose: it is the transport's backpressure. The consumer is always about
         * to take one, so the buffer only ever holds a burst — and a full buffer means there is
         * always something to take, so it cannot hide a hang from the idle deadline.
         */
        private const val EVENT_BUFFER: Int = 64
    }
}
