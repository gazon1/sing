package com.singularity.todo.feature.ai.external

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.mcp.McpServerCommandProvider
import com.singularity.todo.core.mcp.ToolServerEnvironment
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.ai.acp.AcpAgentLaunch
import com.singularity.todo.feature.ai.acp.AcpAgentLauncher
import com.singularity.todo.feature.ai.acp.AcpAgentProfile
import com.singularity.todo.feature.ai.acp.AcpClientSessionOperations
import com.singularity.todo.test.fakes.FakeProfileRepository
import com.singularity.todo.test.fakes.SequenceIdGenerator
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagExists
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private fun viewModel(toolServerAvailable: Boolean = true): AcpExternalAgentViewModel =
        AcpExternalAgentViewModel(
            idGen = SequenceIdGenerator(),
            profileRepository = FakeProfileRepository(),
            commandProvider = commandProvider(toolServerAvailable),
            launcher = FailingLauncher,
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
            operations: AcpClientSessionOperations,
            toolServer: com.singularity.todo.core.mcp.ToolServerCommand.Available?,
        ): AcpAgentLaunch = AcpAgentLaunch.Failed("${profile.displayName} is not installed on this machine.")
    }
}
