@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.external

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.mcp.McpServerCommandProvider
import com.singularity.todo.core.mcp.ToolServerEnvironment
import com.singularity.todo.feature.ai.acp.AcpAgentInfo
import com.singularity.todo.feature.ai.acp.AcpAgentLaunch
import com.singularity.todo.feature.ai.acp.AcpAgentLauncher
import com.singularity.todo.feature.ai.acp.AcpAgentProfile
import com.singularity.todo.feature.ai.acp.AcpAgentSession
import com.singularity.todo.feature.ai.acp.AcpLifecycleSink
import com.singularity.todo.feature.ai.acp.AcpPermissionOption
import com.singularity.todo.feature.ai.acp.AcpPermissionRequest
import com.singularity.todo.feature.ai.acp.AcpSessionPermissions
import com.singularity.todo.feature.ai.acp.AcpTurnEvent
import com.singularity.todo.feature.ai.acp.PermissionDecision
import com.singularity.todo.test.fakes.FakeProfileRepository
import com.singularity.todo.test.fakes.SequenceIdGenerator
import com.singularity.todo.test.helpers.TestVmContext
import com.singularity.todo.test.helpers.testVmContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The external-agent screen's ViewModel.
 *
 * ## The cases that matter
 *
 * - **A permission request is answered.** The agent asks, the reader answers, and the
 *   agent is told which of *its own* options was chosen.
 * - **An unanswered request declines itself.** Five minutes later the call does not
 *   happen. A request nobody is looking at is not a granted one.
 * - **A cancelled turn does not leave a request hanging.** Cancelling ends the turn and
 *   refuses what was asked.
 * - **A second message is refused while a turn runs.** Two turns at once would interleave
 *   two answers into one conversation.
 * - **A failure is a failure.** A turn whose stream ends without a completion is failed,
 *   and never retried.
 * - **The warning survives.** An agent without tool access stays without tool access, and
 *   the reader keeps being told.
 *
 * ## Why the clock is moved by hand
 *
 * The permission request has a deadline, and `advanceUntilIdle()` would jump the virtual
 * clock straight to it — which would answer every permission test with "declined" and
 * pass for the wrong reason. So the tests that need the reader to answer first use
 * `runCurrent()`, which runs what is due *now* and leaves the clock alone.
 */
@Tag("fast")
class AcpExternalAgentViewModelTest {

    // ── Permission ─────────────────────────────────────────────────────────────

    @Test
    fun `a permission request the reader allows resolves as the agent's allow option`() = runTest {
        val session = ScriptedSession {
            it.askForPermission()
            true
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        assertNotNull(ctx.state.value.permission, "the request is on screen before it is answered")

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.AllowPermission(ALLOW_OPTION_ID))
        runCurrent()

        val outcome = session.permissionOutcome
        assertIs<PermissionDecision.Selected>(outcome, "the agent is told which option was chosen")
        assertEquals(ALLOW_OPTION_ID, outcome.optionId)
    }

    @Test
    fun `a permission request the reader refuses resolves as the agent's reject option`() = runTest {
        val session = ScriptedSession {
            it.askForPermission()
            true
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.DenyPermission)
        runCurrent()

        val outcome = session.permissionOutcome
        assertIs<PermissionDecision.Declined>(outcome, "a refusal is the reader's refusal")
    }

    @Test
    fun `a permission request nobody answers declines itself`() = runTest {
        val session = ScriptedSession {
            it.askForPermission()
            true
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        assertNotNull(ctx.state.value.permission, "the request waits")

        advanceTimeBy(AcpExternalAgentViewModel.PERMISSION_TIMEOUT.inWholeMilliseconds + 1_000)
        runCurrent()

        val outcome = session.permissionOutcome
        assertIs<PermissionDecision.Declined>(outcome, "an unanswered request is a refusal")
        assertNull(ctx.state.value.permission, "and the request is off the screen")
    }

    @Test
    fun `a cancelled turn refuses the request that was outstanding`() = runTest {
        val session = ScriptedSession {
            it.askForPermission()
            true
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Cancel)
        runCurrent()

        assertEquals(AgentTurnOutcome.Cancelled, ctx.state.value.turnOutcome, "the turn is cancelled")
        val outcome = session.permissionOutcome
        assertIs<PermissionDecision.Declined>(outcome, "the outstanding request is refused")
    }

    @Test
    fun `an agent that ignores the cancellation is ended by the application`() = runTest {
        val session = ScriptedSession { _ ->
            // An agent that does not stop when it is told to: the turn goes on.
            kotlinx.coroutines.awaitCancellation()
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Cancel)
        runCurrent()

        assertTrue(session.cancelled, "the agent is asked to stop first")
        assertEquals(AgentTurnOutcome.Cancelled, ctx.state.value.turnOutcome, "and the reader is told at once")

        advanceTimeBy(AcpExternalAgentViewModel.CANCEL_GRACE.inWholeMilliseconds + 1_000)
        runCurrent()

        assertTrue(
            session.terminated,
            "an agent that does not stop is ended by the application: a reader who pressed " +
                "cancel must not watch the turn continue.",
        )

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Disconnect)
        runCurrent()
    }

    @Test
    fun `an agent that stops when asked is left alone`() = runTest {
        val session = ScriptedSession { s ->
            // The agent stops when it is asked to, and ends the turn properly.
            s.cancelledSignal.await()
            true
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Cancel)
        runCurrent()

        advanceTimeBy(AcpExternalAgentViewModel.CANCEL_GRACE.inWholeMilliseconds + 1_000)
        runCurrent()

        assertTrue(session.cancelled, "the agent was asked to stop")
        assertFalse(session.terminated, "an agent that stopped on its own is not torn down")

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Disconnect)
        runCurrent()
    }

    // ── The turn ───────────────────────────────────────────────────────────────

    @Test
    fun `a second message is refused while a turn is running`() = runTest {
        val session = ScriptedSession { _ ->
            // A turn that never finishes on its own: the reader has to stop it.
            kotlinx.coroutines.awaitCancellation()
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.InputChanged("and another thing"))
        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Send)
        runCurrent()

        assertEquals(1, session.prompts.size, "the second message was not sent")

        // The turn is still running; the session is closed, so the test does not leave
        // a coroutine behind for the harness to wait for.
        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Disconnect)
        runCurrent()
    }

    @Test
    fun `a turn that ends without a completion is failed and not retried`() = runTest {
        val session = ScriptedSession { _ ->
            // The stream ends with no completion event: the agent did not finish.
            false
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        val outcome = ctx.state.value.turnOutcome
        assertIs<AgentTurnOutcome.Failed>(outcome, "the absence of a completion is a failure")
        assertEquals(1, session.prompts.size, "and the turn is not retried")
    }

    @Test
    fun `the answer streams into the conversation`() = runTest {
        val session = ScriptedSession { _ ->
            // The fake emits the answer itself; the script only says the turn ended.
            true
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        val assistant = ctx.state.value.messages.lastOrNull { !it.fromUser }
        assertNotNull(assistant, "the agent has a message of its own")
    }

    @Test
    fun `a connect that is cancelled ends the agent`() = runTest {
        val session = ScriptedSession { true }
        // The handshake never finishes: the agent does not answer.
        session.connectBlocks = true
        val scope = AutoCloseableCoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val ctx = viewModel(session, scope = scope)

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Connect)
        runCurrent()

        assertFalse(session.terminated, "the agent is still connecting")

        // The reader navigates away. The screen's scope is closed, and the handshake is
        // cancelled with it — and the agent's process was spawned before the handshake,
        // so it exists whether or not the handshake finished.
        scope.close()
        runCurrent()

        assertTrue(session.terminated, "the agent's process was ended")
    }

    @Test
    fun `a stopped turn cannot end the turn that follows it`() = runTest {
        // The first turn waits for the test to let it end; the second never ends on
        // its own, so the state has to keep saying that a turn is running.
        val firstTurnMayFinish = CompletableDeferred<Unit>()
        val session = ScriptedSession { me ->
            if (me.prompts.size == 1) {
                firstTurnMayFinish.await()
                // The first turn ends *late* — after the next one has begun — and it
                // ends with a completion, which is the event that would finalise a turn
                // if the outcome did not know which turn it belonged to.
                true
            } else {
                awaitCancellation()
            }
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        // The reader stops the turn. The turn's job keeps running for a moment — the
        // agent is asked to stop, and the request is a request — so the reader can
        // send another message while it is still winding down.
        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Cancel)
        runCurrent()
        assertIs<AgentTurnOutcome.Cancelled>(ctx.state.value.turnOutcome, "the turn was stopped")

        // The first turn is released *before* the second one starts, so its completion
        // is already waiting when the second turn begins. The first turn's outcome
        // belongs to a turn that is no longer the current one, and it must not end the
        // turn the reader is looking at.
        firstTurnMayFinish.complete(Unit)
        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.InputChanged("and another thing"))
        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Send)
        runCurrent()

        assertTrue(ctx.state.value.isRunning, "the second turn is still running")
        assertNull(ctx.state.value.turnOutcome, "and the first turn did not end it")

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Disconnect)
        runCurrent()
    }

    // ── The warning ────────────────────────────────────────────────────────────

    @Test
    fun `the state claims no tool access before there is a session`() = runTest {
        val session = ScriptedSession { true }
        val ctx = viewModel(session)

        assertIs<AcpToolAccess.Unknown>(
            ctx.state.value.toolAccess,
            "Nothing is claimed before anything was negotiated: a default that says " +
                "'granted' is a claim the app cannot back",
        )
    }

    /**
     * The connect outcome carries the protocol version, and the build of the tool server
     * the app handed over.
     *
     * The protocol version is a real comparison — the handshake refused an agent that
     * spoke another — and the screen names it. The tool-server build is *reported*, not
     * compared: the app knows which build it offered and cannot see which one the agent
     * started, so the outcome states it so the reader knows which build answers. The build
     * is the app's own version, read from a system property on the JVM; in a test it is
     * the fallback, so this asserts the shape and not a literal. See ADR
     * `2026-10-25-the-version-we-can-compare`.
     */
    @Test
    fun `the connect outcome carries the protocol version and the tool-server build`() = runTest {
        val session = ScriptedSession { true }
        val ctx = viewModel(session)

        connect(ctx)

        val connection = ctx.state.value.connection
        assertIs<AcpConnection.Connected>(connection, "the handshake answered")
        assertEquals(1, connection.protocolVersion, "the version the agent speaks")
        assertTrue(
            connection.toolServerBuild.isNotBlank(),
            "the build of the tool server handed over is reported: ${connection.toolServerBuild}",
        )
    }

    @Test
    fun `the answer to a permission request stays on the screen`() = runTest {
        val session = ScriptedSession {
            it.askForPermission()
            true
        }
        val ctx = viewModel(session)

        connect(ctx)
        send(ctx)
        runCurrent()

        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.AllowPermission(ALLOW_OPTION_ID))
        runCurrent()

        val answer = ctx.state.value.permissionAnswer
        assertNotNull(answer, "the decision leaves a trace: the sheet closes, and the screen keeps it")
        assertEquals("Read file", answer.request.title, "next to the question it was about")
        assertIs<PermissionDecision.Selected>(answer.decision, "and it is the decision that was made")
    }

    @Test
    fun `the no-tool-access warning survives the first reply`() = runTest {
        val session = ScriptedSession { true }
        val ctx = viewModel(session, toolServerAvailable = false)

        connect(ctx)
        send(ctx)
        runCurrent()

        val access = ctx.state.value.toolAccess
        assertIs<AcpToolAccess.Unavailable>(access, "the agent cannot reach the user's tasks")
        assertTrue(access.reason.isNotBlank(), "and the reason says why")
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private fun TestScope.viewModel(
        session: ScriptedSession,
        toolServerAvailable: Boolean = true,
        scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(coroutineContext),
    ): TestVmContext<AcpExternalAgentViewModel, AcpExternalAgentUiState> = testVmContext(
        vm = AcpExternalAgentViewModel(
            idGen = SequenceIdGenerator(),
            profileRepository = FakeProfileRepository(),
            commandProvider = commandProvider(toolServerAvailable),
            launcher = FixedLauncher(session),
            scope = scope,
        ),
        stateOf = { it.stateFlow },
        scope = this,
        backgroundScope = backgroundScope,
    )

    private fun commandProvider(available: Boolean): McpServerCommandProvider {
        val java = System.getProperty("java.home") + "/bin/java"
        return McpServerCommandProvider(
            ToolServerEnvironment(
                packaged = false,
                launcher = null,
                javaBinary = if (available) java else null,
                classpath = if (available) "/tmp/classpath" else "",
            ),
        )
    }

    // The helpers take the context as a parameter rather than as a receiver: the
    // virtual clock belongs to the test's scope, and `runCurrent` is its function.
    private fun TestScope.connect(ctx: TestVmContext<AcpExternalAgentViewModel, AcpExternalAgentUiState>) {
        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Connect)
        runCurrent()
    }

    private fun TestScope.send(ctx: TestVmContext<AcpExternalAgentViewModel, AcpExternalAgentUiState>) {
        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.InputChanged("hello"))
        ctx.vm.onIntent(AcpExternalAgentViewModel.Intent.Send)
        runCurrent()
    }

    /**
     * A session that runs a script instead of talking to an agent.
     *
     * The script is where the permission request is made, because that is the only way
     * the request reaches the ViewModel in production: the agent's process asks for it.
     */
    private class ScriptedSession(private val script: suspend (ScriptedSession) -> Boolean) : AcpAgentSession {

        val prompts = mutableListOf<String>()
        var permissionOutcome: PermissionDecision? = null
        var cancelled = false
        var closed = false

        /** Set when the application ended the agent rather than the agent agreeing to stop. */
        var terminated = false

        /** Set when the handshake never finishes: the agent does not answer. */
        var connectBlocks = false

        /** Completes when the agent is asked to stop, so a script can end its turn then. */
        val cancelledSignal = CompletableDeferred<Unit>()

        private var permissions: AcpSessionPermissions? = null

        fun bind(permissions: AcpSessionPermissions) {
            this.permissions = permissions
        }

        /** Makes the request the ViewModel is expected to put on screen. */
        suspend fun askForPermission(): PermissionDecision {
            val decision = permissions!!.requestPermission(permissionRequest())
            permissionOutcome = decision
            return decision
        }

        override suspend fun connect(): AcpAgentInfo {
            if (connectBlocks) awaitCancellation()
            return AcpAgentInfo("scripted-agent", "1.0", 1)
        }

        override fun prompt(message: String): Flow<AcpTurnEvent> = flow {
            prompts += message
            emit(AcpTurnEvent.Activity(AgentEvent.TextDelta("hello")))
            // The script says whether the agent completed the turn. A script that ends
            // without saying so is a turn that ended without a completion — the failure
            // the outcome has to report.
            if (script(this@ScriptedSession)) {
                emit(AcpTurnEvent.Finished(AgentTurnOutcome.Completed()))
            }
        }

        override suspend fun cancel() {
            cancelled = true
            cancelledSignal.complete(Unit)
        }

        override suspend fun close() {
            closed = true
        }

        override fun terminate() {
            terminated = true
        }
    }

    /** A launcher that hands back the session the test wrote. */
    private class FixedLauncher(private val session: ScriptedSession) : AcpAgentLauncher {
        override fun launch(
            profile: AcpAgentProfile,
            scope: CoroutineScope,
            permissions: AcpSessionPermissions,
            toolServer: com.singularity.todo.core.mcp.ToolServerCommand.Available?,
            sessionEvents: AcpLifecycleSink,
        ): AcpAgentLaunch {
            session.bind(permissions)
            return AcpAgentLaunch.Started(session)
        }
    }
}

/** The request the fake agent asks permission for. */
private fun permissionRequest() = AcpPermissionRequest(
    toolCallId = "call-1",
    title = "Read file",
    options = listOf(
        AcpPermissionOption(optionId = ALLOW_OPTION_ID, label = "Allow once", isRejection = false),
        AcpPermissionOption(optionId = REJECT_OPTION_ID, label = "Reject", isRejection = true),
    ),
)

private const val ALLOW_OPTION_ID = "allow-once"
private const val REJECT_OPTION_ID = "reject-once"
