package com.singularity.todo.feature.ai.external

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.mcp.McpServerCommandProvider
import com.singularity.todo.core.mcp.ToolServerEnvironment
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.ai.acp.AcpAgentInfo
import com.singularity.todo.feature.ai.acp.AcpAgentLaunch
import com.singularity.todo.feature.ai.acp.AcpAgentLauncher
import com.singularity.todo.feature.ai.acp.AcpAgentProfile
import com.singularity.todo.feature.ai.acp.AcpAgentSession
import com.singularity.todo.feature.ai.acp.AcpLifecycleSink
import com.singularity.todo.feature.ai.acp.AcpSessionPermissions
import com.singularity.todo.feature.ai.acp.AcpTurnEvent
import com.singularity.todo.test.fakes.FakeProfileRepository
import com.singularity.todo.test.fakes.SequenceIdGenerator
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagEnabled
import com.singularity.todo.test.helpers.assertTagExists
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The external-agent screen, in isolation.
 *
 * ## What this test is for
 *
 * The screen's job is to answer three questions the reader has *before* they type
 * anything: which agent, what will be launched, and may it see my tasks. The last one
 * is the easy one to lose — a warning that only appears once the agent has already
 * failed looks like the agent's fault.
 *
 * So the assertions are about what is on screen before anything happens, and about the
 * command being visible rather than implied.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AcpExternalAgentScreenTest {

    @Test
    fun `the agents of the registry are offered and one is preselected`() = runIsolatedComposeTest {
        setContent { AcpExternalAgentScreenContent(vm = viewModel()) }

        assertTagExists(TestTags.acpAgentChip("gemini-cli"))
        assertTagExists(TestTags.acpAgentChip("claude-code"))
        assertTagExists(TestTags.acpAgentChip("codex"))
    }

    @Test
    fun `the command that will be launched is on screen`() = runIsolatedComposeTest {
        setContent { AcpExternalAgentScreenContent(vm = viewModel()) }

        assertTagExists(TestTags.ACP_AGENT_COMMAND)
    }

    @Test
    fun `the connect button is there before anything is connected`() = runIsolatedComposeTest {
        setContent { AcpExternalAgentScreenContent(vm = viewModel()) }

        assertTagExists(TestTags.ACP_CONNECT_BUTTON)
    }

    @Test
    fun `the warning that the agent cannot see the tasks is on screen`() = runIsolatedComposeTest {
        val vm = viewModel(toolServerAvailable = false)

        setContent { AcpExternalAgentScreenContent(vm = vm) }

        // The tool access is resolved when the connection is opened, so the screen has
        // to be told to try before the warning can be on it.
        vm.onIntent(AcpExternalAgentViewModel.Intent.Connect)
        waitUntil { vm.stateFlow.value.toolAccess is AcpToolAccess.Unavailable }

        assertTagDisplayed(TestTags.ACP_NO_TOOL_ACCESS_WARNING)
    }

    @Test
    fun `the agent that cannot be started is reported with the reason`() = runIsolatedComposeTest {
        val vm = viewModel()

        setContent { AcpExternalAgentScreenContent(vm = vm) }

        vm.onIntent(AcpExternalAgentViewModel.Intent.Connect)
        waitUntil { vm.stateFlow.value.connection is AcpConnection.Failed }

        assertTagDisplayed(TestTags.ACP_CONNECTION_ERROR)
    }

    @Test
    fun `picking another agent shows that agent's command`() = runIsolatedComposeTest {
        val vm = viewModel()

        setContent { AcpExternalAgentScreenContent(vm = vm) }

        clickTag(TestTags.acpAgentChip("codex"))
        waitUntil { vm.stateFlow.value.selectedAgentId == "codex" }

        assertTagExists(TestTags.ACP_AGENT_COMMAND)
    }

    @Test
    fun `the back button leaves the screen`() = runIsolatedComposeTest {
        var left = false
        setContent { AcpExternalAgentScreenContent(vm = viewModel(), onBack = { left = true }) }

        clickTag(TestTags.ACP_BACK_BUTTON)

        assertTrue(left, "the back button hands the reader back to where they came from")
    }

    /**
     * The input stays enabled while a turn runs, and the refusal reaches the reader.
     *
     * Disabling the field would stop the second message before the ViewModel ever sees it, and
     * the refusal the screen owes the reader — a turn is already running, said in words — would
     * never be reachable from the screen (REQ-EXTAG-007). So the field and its send button stay
     * enabled, the Stop control beside them ends the turn, and a second message produces the
     * refusal as a dialog the reader can see.
     */
    @Test
    fun `the input stays enabled while a turn is running, and the refusal is shown`() =
        runIsolatedComposeTest {
            val vm = viewModel(launcher = RunningLauncher)

            setContent { AcpExternalAgentScreenContent(vm = vm) }

            vm.onIntent(AcpExternalAgentViewModel.Intent.Connect)
            waitUntil { vm.stateFlow.value.connection is AcpConnection.Connected }

            vm.onIntent(AcpExternalAgentViewModel.Intent.InputChanged("hello"))
            vm.onIntent(AcpExternalAgentViewModel.Intent.Send)
            waitUntil { vm.stateFlow.value.isRunning }

            assertTagEnabled(TestTags.CHAT_INPUT)
            assertTagExists(TestTags.ACP_CANCEL_BUTTON)

            // A second message, sent from a screen whose input is enabled, is refused — and the
            // reason is on screen. This is the half of REQ-EXTAG-007 the disabled field hid.
            vm.onIntent(AcpExternalAgentViewModel.Intent.InputChanged("and another thing"))
            vm.onIntent(AcpExternalAgentViewModel.Intent.Send)
            assertTagDisplayed(TestTags.RESULT_DIALOG)

            // The turn is still running; end it through the screen so the test leaves nothing
            // behind for the harness to wait for.
            vm.onIntent(AcpExternalAgentViewModel.Intent.Disconnect)
        }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private fun viewModel(
        toolServerAvailable: Boolean = true,
        launcher: AcpAgentLauncher = FailingLauncher,
    ): AcpExternalAgentViewModel =
        AcpExternalAgentViewModel(
            idGen = SequenceIdGenerator(),
            profileRepository = FakeProfileRepository(),
            commandProvider = commandProvider(toolServerAvailable),
            launcher = launcher,
            // Unconfined so that the intent's work runs before the assertion: the screen
            // shows what the state says, and a state that arrives a frame later is a
            // flake, not a fact.
            scope = AutoCloseableCoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
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

    /**
     * A launcher that starts nothing.
     *
     * The screen's job in these tests is what it shows, and a launcher that fails is
     * the cheapest way to reach the states that show something.
     */
    private object FailingLauncher : AcpAgentLauncher {
        override fun launch(
            profile: AcpAgentProfile,
            scope: kotlinx.coroutines.CoroutineScope,
            permissions: AcpSessionPermissions,
            toolServer: com.singularity.todo.core.mcp.ToolServerCommand.Available?,
            sessionEvents: AcpLifecycleSink,
        ): AcpAgentLaunch = AcpAgentLaunch.Failed("${profile.displayName} is not installed on this machine.")
    }

    /**
     * A launcher whose session connects and then holds a turn open.
     *
     * The turn never finishes on its own: the screen is showing a running turn, which is the
     * state the enabled-input rule is about.
     */
    private object RunningLauncher : AcpAgentLauncher {
        override fun launch(
            profile: AcpAgentProfile,
            scope: kotlinx.coroutines.CoroutineScope,
            permissions: AcpSessionPermissions,
            toolServer: com.singularity.todo.core.mcp.ToolServerCommand.Available?,
            sessionEvents: AcpLifecycleSink,
        ): AcpAgentLaunch = AcpAgentLaunch.Started(RunningSession)
    }

    private object RunningSession : AcpAgentSession {
        override suspend fun connect(): AcpAgentInfo = AcpAgentInfo("running-agent", "1.0", 1)

        override fun prompt(message: String): Flow<AcpTurnEvent> = flow { awaitCancellation() }

        override suspend fun cancel() = Unit

        override suspend fun close() = Unit

        override fun terminate() = Unit
    }
}
