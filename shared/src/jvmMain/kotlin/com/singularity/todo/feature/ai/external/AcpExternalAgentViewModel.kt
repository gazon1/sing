package com.singularity.todo.feature.ai.external

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
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
import com.singularity.todo.feature.ai.acp.AcpAgentLauncher
import com.singularity.todo.feature.ai.acp.AcpAgentProfile
import com.singularity.todo.feature.ai.acp.AcpAgentRegistry
import com.singularity.todo.feature.ai.acp.AcpPermissionRequest
import com.singularity.todo.feature.ai.acp.PermissionDecision
import com.singularity.todo.feature.ai.acp.SubprocessAcpAgentLauncher
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlin.time.Duration
import kotlinx.coroutines.launch

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
    val toolAccess: AcpToolAccess = AcpToolAccess.Unknown,
    /** The permission request on screen, or null. */
    val permission: AcpPermissionRequest? = null,
    /**
     * The last permission answer, so the reader can see what was decided.
     *
     * A request that was answered and then vanished from the screen leaves the reader with
     * nothing: the answer happened, and the screen shows no trace of it.
     */
    val permissionAnswer: AcpPermissionAnswer? = null,
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
    /**
     * Nothing is known yet.
     *
     * There is no session, so there is no access to speak of. The default must not claim
     * access: a state that says "granted" before anything was negotiated is a claim the
     * app cannot back, and it is exactly what a failed handshake used to leave behind.
     */
    data object Unknown : AcpToolAccess

    /** The agent is connected and may reach the user's tasks. */
    data object Granted : AcpToolAccess

    /** The agent cannot reach the user's tasks, and this says why. */
    data class Unavailable(val reason: String) : AcpToolAccess
}

/**
 * What the reader decided about a permission request.
 *
 * The request and the decision together, because a decision without its question says
 * nothing: "allowed" is only meaningful next to what was allowed.
 */
data class AcpPermissionAnswer(val request: AcpPermissionRequest, val decision: PermissionDecision)

/** One line of the conversation. */
data class AcpExternalMessage(val id: String, val fromUser: Boolean, val text: String)

/**
 * The ViewModel of the external-agent screen.
 *
 * ## What it owns
 *
 * The state the screen draws: the agent, the connection, the conversation so far, the
 * turn, and the permission request on screen. The work behind those three — the
 * conversation ([AcpAgentConversation]), the turn ([AcpTurnRunner]) and the permission
 * handshake ([AcpPermissionBroker]) — belongs to three collaborators, so that a reader
 * of this class is reading the screen and not the protocol.
 *
 * The scope is injected and registered with the ViewModel, so closing the screen ends
 * the agent and everything it started — not just the coroutines.
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

    /**
     * The conversation: the agent's process and the session on top of it.
     *
     * The ViewModel keeps the state the screen draws; the process belongs to
     * [AcpAgentConversation], which knows how to end it.
     */
    private val conversation = AcpAgentConversation(vmScope, launcher)

    /**
     * The turn that is running, and the rules about when it may write to the screen.
     *
     * The ViewModel keeps the state the screen draws; the turn's clock belongs to
     * [AcpTurnRunner].
     */
    private val turnRunner = AcpTurnRunner(
        scope = vmScope,
        onActivity = { assistantId, event -> applyActivity(assistantId, event) },
        onOutcome = { outcome -> setOutcome(outcome) },
    )

    /** The permission request on screen, and the answer the reader gives. */
    private val permissions = AcpPermissionBroker()

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

    init {
        // The agent's process is ended when the ViewModel is cleared, and it is ended
        // through a closeable rather than through the scope: the scope is closed first,
        // so a coroutine launched there would never run.
        addCloseable { conversation.terminate() }
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
            when (
                val outcome = conversation.connect(
                    agent = agent,
                    toolServer = toolServerCommand() as? ToolServerCommand.Available,
                    askForPermission = ::askForPermission,
                )
            ) {
                is AcpConversationOutcome.Connected ->
                    updateState {
                        it.copy(connection = AcpConnection.Connected(outcome.agentName), toolAccess = toolAccess)
                    }

                is AcpConversationOutcome.LaunchFailed ->
                    updateState {
                        it.copy(
                            connection = AcpConnection.Failed(outcome.reason),
                            toolAccess = AcpToolAccess.Unavailable(
                                "The agent did not start, so it cannot reach your tasks.",
                            ),
                        )
                    }

                is AcpConversationOutcome.HandshakeFailed ->
                    updateState {
                        it.copy(
                            connection = AcpConnection.Failed(outcome.reason),
                            toolAccess = AcpToolAccess.Unavailable(
                                "The agent did not connect, so it cannot reach your tasks.",
                            ),
                        )
                    }

                AcpConversationOutcome.Closed -> Unit
            }
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
        turnRunner.close()
        conversation.close()
        permissions.decline()
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

    private fun send() {
        val text = _state.value.input.text.trim()
        if (text.isEmpty()) return
        val openSession = conversation.openSession ?: return
        if (_state.value.isRunning) {
            // The refusal is a message, not a silence: the reader pressed Send and
            // nothing happened, and "nothing happened" is the answer that leaves the
            // reader pressing it again.
            tryEmit(UiEvent.Error("A turn is already running. Stop it before sending another."))
            return
        }

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

        turnRunner.start(assistantId) { sink ->
            openSession.prompt(text).collect { event -> sink(event) }
        }
    }

    /**
     * Records how the turn ended.
     *
     * The runner has already decided that this outcome belongs to the turn on screen
     * and that the turn has not ended yet; all that is left is to say it.
     */
    private fun setOutcome(outcome: AgentTurnOutcome) {
        updateState { it.copy(isRunning = false, turnOutcome = outcome) }
    }

    /**
     * Records what the agent did.
     *
     * The runner has already decided that this activity belongs to the turn on screen;
     * all that is left is to show it.
     */
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
     * working. So the turn is given [AcpTurnRunner.CANCEL_GRACE] to end by itself, and
     * if it does not, the agent is ended: the reader pressed cancel, and the turn is
     * over whether the agent agrees or not.
     */
    private fun cancel() {
        val openSession = conversation.openSession ?: return
        val turn = turnRunner.captureRunningTurn()
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
            permissions.decline()
            turnRunner.recordOutcome(turn.turnId, AgentTurnOutcome.Cancelled)
            updateState { it.copy(permission = null) }

            turnRunner.endTurnThatOutlivedTheRequest(openSession, turn.job)
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
        val decision = permissions.begin(request)
        updateState { it.copy(permission = permissions.onScreen()) }
        return try {
            permissions.await(decision)
        } finally {
            updateState { it.copy(permission = permissions.onScreen()) }
        }
    }

    private fun answerPermission(decision: PermissionDecision) {
        val request: AcpPermissionRequest = _state.value.permission ?: return
        permissions.answer(decision)
        updateState { it.copy(permissionAnswer = AcpPermissionAnswer(request, decision)) }
    }

    companion object {
        /** How long a permission request waits before it declines itself. */
        val PERMISSION_TIMEOUT: Duration = AcpPermissionBroker.PERMISSION_TIMEOUT

        /**
         * How long an agent is given to end a cancelled turn on its own.
         *
         * The protocol's cancellation is a request, and an agent is free to ignore it.
         * The manual pass found one that does: Claude Code's bridge answers
         * `session/cancel` with "method not found" and keeps working. So the screen
         * asks, waits a moment, and then ends the agent itself — a reader who pressed
         * cancel must not watch the turn continue.
         */
        val CANCEL_GRACE: Duration = AcpTurnRunner.CANCEL_GRACE
    }
}
