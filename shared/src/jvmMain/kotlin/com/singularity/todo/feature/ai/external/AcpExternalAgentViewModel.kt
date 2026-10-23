package com.singularity.todo.feature.ai.external

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.llm.AgentActivityState
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.mcp.McpServerCommandProvider
import com.singularity.todo.core.mcp.ToolServerCommand
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.text.StaticText
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.ai.acp.AcpAgentLaunch
import com.singularity.todo.feature.ai.acp.AcpAgentLauncher
import com.singularity.todo.feature.ai.acp.AcpAgentProfile
import com.singularity.todo.feature.ai.acp.AcpAgentRegistry
import com.singularity.todo.feature.ai.acp.AcpAgentSession
import com.singularity.todo.feature.ai.acp.AcpClientSessionOperations
import com.singularity.todo.feature.ai.acp.AcpPermissionRequest
import com.singularity.todo.feature.ai.acp.AcpTurnEvent
import com.singularity.todo.feature.ai.acp.PermissionDecision
import com.singularity.todo.feature.ai.acp.SubprocessAcpAgentLauncher
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The state of a conversation with an external agent.
 *
 * Everything the reader needs to answer "what is happening" is here: who the agent is,
 * whether it is connected, what it has done, and whether it may touch the user's tasks.
 */
data class AcpExternalAgentUiState(
    /** The agents on offer. */
    val agents: List<AcpAgentProfile> = AcpAgentRegistry.entries,
    /** Which agent is selected. */
    val selectedAgentId: String = AcpAgentRegistry.default.id,
    /** Whether there is a conversation, and how it is going. */
    val connection: AcpConnection = AcpConnection.Disconnected,
    /** The conversation so far. */
    val messages: List<AcpExternalMessage> = emptyList(),
    /** The text of the input. */
    val input: FieldOwnedText = FieldOwnedText.empty(),
    /** Whether a turn is running. */
    val isRunning: Boolean = false,
    /** What the agent has done in the current turn. */
    val activity: AgentActivityState = AgentActivityState(),
    /** How the last turn ended, or null while one is running. */
    val turnOutcome: AgentTurnOutcome? = null,
    /** Whether the agent may reach the user's tasks. */
    val toolAccess: AcpToolAccess = AcpToolAccess.Granted,
    /** The permission request on screen, or null. */
    val permission: AcpPermissionRequest? = null,
) {
    /** The input as the field's source. */
    val inputSource: StaticText get() = StaticText(input.text)

    /** The selected agent, or null when the registry does not hold it. */
    val selectedAgent: AcpAgentProfile? get() = agents.firstOrNull { it.id == selectedAgentId }
}

/** Whether the conversation is running, and why not when it is not. */
sealed interface AcpConnection {
    data object Disconnected : AcpConnection
    data object Connecting : AcpConnection
    data class Connected(val agentName: String) : AcpConnection
    data class Failed(val reason: String) : AcpConnection
}

/**
 * Whether the agent may reach the user's tasks.
 *
 * [Unavailable] always carries a reason: the reader has to be able to say *why* the
 * agent is blind to their tasks, and a bare "no" is not an answer.
 */
sealed interface AcpToolAccess {
    data object Granted : AcpToolAccess
    data class Unavailable(val reason: String) : AcpToolAccess
}

/** One line of the conversation. */
data class AcpExternalMessage(val id: String, val fromUser: Boolean, val text: String)

/**
 * The ViewModel of the external-agent screen.
 *
 * ## What it owns
 *
 * The conversation: the agent's process, the session on top of it, and the process tree
 * that ends both. The scope is injected and registered with the ViewModel, so closing
 * the screen ends the agent and everything it started — not just the coroutines.
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
 * There is no retry anywhere in this class. An agent that failed mid-turn failed for a
 * reason the user cannot see, and repeating the request would repeat the failure with
 * a second copy of whatever it had already done.
 *
 * ## A permission request waits, and then declines
 *
 * A request suspends until the reader answers. Five minutes later it declines itself:
 * an unanswered request is not a granted one, and a request nobody is looking at must
 * not hold a tool call open forever.
 */
class AcpExternalAgentViewModel(
    private val idGen: IdGenerator,
    private val profileRepository: ProfileRepository,
    private val commandProvider: McpServerCommandProvider,
    private val launcher: AcpAgentLauncher = SubprocessAcpAgentLauncher,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<AcpExternalAgentUiState, AcpExternalAgentViewModel.Intent, AcpExternalAgentViewModel.UiEvent>(
        initialState = AcpExternalAgentUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    sealed interface Intent : MviIntent {
        /** The user picked a different agent. */
        data class SelectAgent(val agentId: String) : Intent

        /** Start the conversation. */
        data object Connect : Intent

        /** End the conversation. */
        data object Disconnect : Intent

        /** The user typed. */
        data class InputChanged(val text: String) : Intent

        /** Send the input. */
        data object Send : Intent

        /** Stop the turn that is running. */
        data object Cancel : Intent

        /** The user allowed the call. */
        data class AllowPermission(val optionId: String) : Intent

        /** The user refused the call. */
        data object DenyPermission : Intent
    }

    /** One-shot events the screen acts on. */
    sealed interface UiEvent : MviEvent {
        data class Error(val message: String) : UiEvent
    }

    private var session: AcpAgentSession? = null
    private var turnJob: Job? = null
    private var pendingPermission: CompletableDeferred<PermissionDecision>? = null

    init {
        // The agent's process is ended when the ViewModel is cleared, and it is ended
        // through a closeable rather than through the scope: the scope is closed first,
        // so a coroutine launched there would never run.
        addCloseable {
            session?.terminate()
            session = null
        }
    }

    override fun onIntent(intent: Intent) {
        when (intent) {
            is Intent.SelectAgent -> updateState { it.copy(selectedAgentId = intent.agentId) }
            Intent.Connect -> connect()
            Intent.Disconnect -> disconnect()
            is Intent.InputChanged -> updateState { it.copy(input = it.input.echoed(intent.text)) }
            Intent.Send -> send()
            Intent.Cancel -> cancel()
            is Intent.AllowPermission -> answerPermission(PermissionDecision.Selected(intent.optionId))
            Intent.DenyPermission -> answerPermission(PermissionDecision.Declined)
        }
    }

    // ── The conversation ────────────────────────────────────────────────────────

    private fun connect() {
        if (_state.value.connection is AcpConnection.Connecting) return
        updateState { it.copy(connection = AcpConnection.Connecting) }

        vmScope.launch {
            val agent = _state.value.selectedAgent
            if (agent == null) {
                updateState { it.copy(connection = AcpConnection.Failed("That agent is not in the registry.")) }
                return@launch
            }

            val toolAccess = resolveToolAccess()
            val operations = AcpClientSessionOperations(::askForPermission)
            when (
                val launch = launcher.launch(
                    profile = agent,
                    scope = vmScope,
                    operations = operations,
                    toolServer = toolServerCommand() as? ToolServerCommand.Available,
                )
            ) {
                is AcpAgentLaunch.Failed -> {
                    updateState {
                        it.copy(connection = AcpConnection.Failed(launch.reason), toolAccess = toolAccess)
                    }
                    return@launch
                }

                is AcpAgentLaunch.Started -> openSession(agent, launch.session, toolAccess)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // the agent can fail in many ways; the screen needs the message
    private suspend fun openSession(
        agent: AcpAgentProfile,
        agentSession: AcpAgentSession,
        toolAccess: AcpToolAccess,
    ) {
        try {
            agentSession.connect()
        } catch (e: CancellationException) {
            // A cancellation that did not come from us is the connection failing, not the
            // screen going away. The client translates the protocol's own cancellation —
            // the one a dead agent raises — into a failure, so a cancellation that
            // reaches this point is the caller's, and it has to survive.
            throw e
        } catch (e: Exception) {
            updateState { it.copy(connection = AcpConnection.Failed(e.message ?: "The agent did not answer.")) }
            runCatchingCancellable { agentSession.close() }
            return
        }

        session = agentSession
        updateState {
            it.copy(connection = AcpConnection.Connected(agent.displayName), toolAccess = toolAccess)
        }
    }

    private fun disconnect() {
        vmScope.launch {
            closeSession()
            updateState {
                AcpExternalAgentUiState(
                    agents = it.agents,
                    selectedAgentId = it.selectedAgentId,
                )
            }
        }
    }

    private suspend fun closeSession() {
        turnJob?.cancel()
        turnJob = null
        session?.let { open -> runCatchingCancellable { open.close() } }
        session = null
        pendingPermission?.complete(PermissionDecision.Declined)
        pendingPermission = null
    }

    /**
     * The command the tool server would be started with, and what it means if there is
     * none.
     *
     * The agent is connected either way. An agent without tool access can still talk —
     * it is simply blind to the user's tasks — and the screen says so before the first
     * message rather than letting the reader wonder why the agent cannot find
     * anything.
     */
    private fun resolveToolAccess(): AcpToolAccess =
        when (toolServerCommand()) {
            is ToolServerCommand.Available -> AcpToolAccess.Granted
            is ToolServerCommand.Unavailable -> AcpToolAccess.Unavailable(reasonOfUnavailable())
        }

    private fun toolServerCommand(): ToolServerCommand =
        commandProvider.commandFor(profileRepository.activeProfileId.value)

    private fun reasonOfUnavailable(): String =
        (toolServerCommand() as? ToolServerCommand.Unavailable)?.reason
            ?: "The tool server command could not be produced."

    // ── A turn ──────────────────────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught") // the agent can fail in many ways; the turn needs the message
    private fun send() {
        val text = _state.value.input.text.trim()
        if (text.isEmpty()) return
        val openSession = session ?: return
        if (_state.value.isRunning) return

        val assistantId = idGen.next()
        updateState {
            it.copy(
                input = it.input.cleared(),
                isRunning = true,
                activity = AgentActivityState(),
                turnOutcome = null,
                messages = it.messages +
                    AcpExternalMessage(idGen.next(), fromUser = true, text = text) +
                    AcpExternalMessage(assistantId, fromUser = false, text = ""),
            )
        }

        turnJob = vmScope.launch {
            var outcome: AgentTurnOutcome? = null
            try {
                openSession.prompt(text).collect { event ->
                    when (event) {
                        is AcpTurnEvent.Activity -> applyActivity(assistantId, event.event)
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
            val finalOutcome = outcome ?: AgentTurnOutcome.Failed("The agent stopped without answering.")
            setOutcome(finalOutcome)
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
     */
    private fun setOutcome(outcome: AgentTurnOutcome) {
        updateState {
            if (it.turnOutcome != null) {
                it
            } else {
                it.copy(isRunning = false, turnOutcome = outcome)
            }
        }
    }

    private fun applyActivity(assistantId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> updateState { state ->
                state.copy(
                    activity = state.activity + event,
                    messages = state.messages.map { message ->
                        if (message.id == assistantId) message.copy(text = message.text + event.text) else message
                    },
                )
            }

            is AgentEvent.ToolCallUpdated -> updateState { it.copy(activity = it.activity + event) }

            is AgentEvent.PlanUpdated -> updateState { it.copy(activity = it.activity + event) }

            AgentEvent.TurnCompleted -> Unit
        }
    }

    /**
     * Stops the turn that is running.
     *
     * A request that was outstanding when the turn was stopped is refused: the reader
     * stopped the turn, and a stopped turn does not get to keep asking.
     *
     * The agent is asked to stop, and the refusal is allowed to reach it: a request
     * that is answered and then abandoned is a request the agent never got an answer
     * to, and the agent is the one that has to know.
     *
     * But asking is not the same as stopping. The protocol's cancellation is a request,
     * and an agent may ignore it — one real agent answers "method not found" and keeps
     * working. So the turn is given [CANCEL_GRACE] to end by itself, and if it does
     * not, the agent is ended: the reader pressed cancel, and the turn is over whether
     * the agent agrees or not.
     */
    private fun cancel() {
        val openSession = session ?: return
        vmScope.launch {
            openSession.cancel()
            // The outstanding request is refused, and the refusal is allowed to reach
            // the agent: the turn is not torn down here. A request that is answered and
            // then abandoned is a request the agent never got an answer to, and the
            // agent is the one that has to know.
            //
            // The turn ends when the agent ends it — which it does, having been told.
            // The job is cancelled in [closeSession], where tearing it down is the
            // point.
            pendingPermission?.complete(PermissionDecision.Declined)
            pendingPermission = null
            setOutcome(AgentTurnOutcome.Cancelled)
            updateState { it.copy(permission = null) }

            endTurnThatOutlivedTheRequest(openSession)
        }
    }

    /**
     * Ends a turn that did not end when it was asked to.
     *
     * The turn's job is waited for, briefly. If it is still running afterwards, the
     * agent is torn down: a process that will not stop is not a turn the reader can
     * leave.
     */
    private suspend fun endTurnThatOutlivedTheRequest(openSession: AcpAgentSession) {
        val running = turnJob ?: return
        val ended = withTimeoutOrNull(CANCEL_GRACE) { running.join() } != null
        if (!ended) {
            openSession.terminate()
        }
    }

    // ── Permission ──────────────────────────────────────────────────────────────

    /**
     * Asks the reader.
     *
     * The request is put on screen and the answer is waited for. Five minutes later the
     * request declines itself: an unanswered request is not a granted one.
     */
    private suspend fun askForPermission(request: AcpPermissionRequest): PermissionDecision {
        val decision = CompletableDeferred<PermissionDecision>()
        pendingPermission = decision
        updateState { it.copy(permission = request) }

        val answer = try {
            withTimeoutOrNull(PERMISSION_TIMEOUT) { decision.await() } ?: PermissionDecision.Declined
        } finally {
            pendingPermission = null
            updateState { it.copy(permission = null) }
        }
        return answer
    }

    private fun answerPermission(decision: PermissionDecision) {
        pendingPermission?.complete(decision)
    }

    companion object {
        /** How long a permission request waits before it declines itself. */
        val PERMISSION_TIMEOUT: Duration = 5.minutes

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
