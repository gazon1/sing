package com.singularity.todo.feature.ai.acp

import com.singularity.todo.core.process.ProcessTree
import com.singularity.todo.core.process.Subprocess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The client, against a **real** agent process.
 *
 * ## Why this test needs a process
 *
 * The session-level behaviour is covered by a fake session, and it should be: those
 * tests are about what the screen does with a turn. This file is about the pipe, and
 * the pipe has exactly five ways to go wrong that a fake session cannot reproduce:
 *
 * - the agent is not installed at all;
 * - the agent's process dies in the middle of a turn;
 * - the agent answers with a protocol version this client does not speak;
 * - the agent accepts the connection and then says nothing;
 * - the agent answers everything but the goodbye.
 *
 * Each of those ends the conversation in a way the reader has to be told about, and
 * each of them is invisible to a double — the double is the answer.
 *
 * ## The agent
 *
 * [FakeAcpAgentKt] in this source set: a program that speaks the protocol over standard
 * input and output, and that can be told to misbehave in each of the five ways above.
 */
@Tag("slow")
@Tag("slow:platform")
class AcpClientProcessTest {

    @Test
    fun `a conversation with a real agent streams the answer and ends the turn`() = runTest {
        val agent = startAgent(MODE_GOOD)

        val client = AcpClient(
            scope = this,
            process = agent.process,
            tree = agent.tree,
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
        )
        val info = try {
            withContext(Dispatchers.IO) { client.connect() }
        } finally {
            // What the agent received and what it said are the diagnostic when it
            // cannot answer.
            println("AGENT TRANSCRIPT: " + runCatching { agent.transcript.readText() }.getOrDefault("<none>"))
            agent.diagnostics.forEach { println("agent stderr: $it") }
        }
        assertEquals("fake-agent", info.name, "the agent says who it is")

        val events = client.prompt("hello").toList()

        val activity = events.filterIsInstance<AcpTurnEvent.Activity>()
        assertTrue(activity.isNotEmpty(), "the agent's message arrived")
        val text = activity.filterIsInstance<AcpTurnEvent.Activity>()
            .mapNotNull { (it.event as? com.singularity.todo.core.llm.AgentEvent.TextDelta)?.text }
            .joinToString("")
        assertEquals("hello from the fake agent", text, "and it is the agent's answer")

        val finished = events.filterIsInstance<AcpTurnEvent.Finished>()
        assertEquals(1, finished.size, "the turn ended exactly once")
        assertEquals(
            com.singularity.todo.core.llm.AgentTurnOutcome.Completed(),
            finished.single().outcome,
            "and it ended successfully",
        )
        client.close()
    }

    @Test
    fun `an agent that is not installed is reported with its command`() {
        val profile = AcpAgentProfile(
            id = "missing",
            displayName = "Missing Agent",
            command = "/nonexistent/definitely-not-an-agent",
        )

        val launch = SubprocessAcpAgentLauncher.launch(
            profile = profile,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
        )

        val failed = launch as? AcpAgentLaunch.Failed
        assertTrue(failed != null, "the agent did not start: $launch")
        assertTrue(
            failed.reason.contains("/nonexistent/definitely-not-an-agent"),
            "the failure names the command that was tried: ${failed.reason}",
        )
    }

    @Test
    fun `an agent that speaks another protocol version is refused`() = runTest {
        val agent = startAgent(MODE_BAD_VERSION)

        val client = AcpClient(
            scope = this,
            process = agent.process,
            tree = agent.tree,
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
        )

        assertFailsWith<Exception> { withContext(Dispatchers.IO) { client.connect() } }
        client.close()
    }

    @Test
    fun `an agent that says nothing fails the connection rather than hanging`() = runTest {
        val agent = startAgent(MODE_SILENT)

        val client = AcpClient(
            scope = this,
            process = agent.process,
            tree = agent.tree,
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
            connectTimeout = 2.seconds,
        )

        // The deadline is short here on purpose: the agent is never going to answer,
        // and the point of the test is that the client gives up rather than waiting.
        assertFailsWith<Exception> { withContext(Dispatchers.IO) { client.connect() } }
        client.close()
    }

    @Test
    fun `an agent that dies mid-turn ends the turn without a completion`() = runTest {
        val agent = startAgent(MODE_CRASH)

        val client = AcpClient(
            scope = this,
            process = agent.process,
            tree = agent.tree,
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
        )
        withContext(Dispatchers.IO) { client.connect() }

        val events = runCatching { client.prompt("hello").toList() }.getOrElse { emptyList() }

        assertTrue(
            events.filterIsInstance<AcpTurnEvent.Finished>().isEmpty(),
            "the turn ended without a completion, which is what a failure is: $events",
        )
        client.close()
    }

    @Test
    fun `an agent that ignores the goodbye does not hang the close`() = runTest {
        val agent = startAgent(MODE_MUTE_CLOSE)

        val client = AcpClient(
            scope = this,
            process = agent.process,
            tree = agent.tree,
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
            closeTimeout = 2.seconds,
        )
        withContext(Dispatchers.IO) { client.connect() }

        // The goodbye is a request, and the protocol library puts no deadline on it.
        // An agent that receives `session/close` and never answers it would leave the
        // disconnect waiting for ever — so the client gives the answer a deadline of
        // its own and tears the agent down either way.
        //
        // The deadline is on the real clock here (the close runs on the IO dispatcher),
        // so the wait is a real two seconds and not a virtual one.
        withContext(Dispatchers.IO) { client.close() }

        assertTrue(
            !agent.process.process.isAlive,
            "the agent's process is gone even though it never answered the goodbye",
        )
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private class RunningAgent(
        val process: com.singularity.todo.core.process.DuplexProcess,
        val tree: ProcessTree,
        /** What the agent said on standard error — the diagnostic when it cannot answer. */
        val diagnostics: List<String>,
        /** What the agent received — the other half of the diagnostic. */
        val transcript: File,
    )

    private fun startAgent(mode: String): RunningAgent {
        // Named "javaBinary", not "java": a local named "java" shadows the package,
        // and every `java.io.File` in this file would stop resolving.
        val javaBinary = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val diagnostics = mutableListOf<String>()
        val transcript = File.createTempFile("fake-agent-", ".log")
        val process = Subprocess.spawnDuplex(
            argv = listOf(javaBinary, "-cp", classpath, FAKE_AGENT_CLASS, mode, transcript.absolutePath),
            stderrSink = { diagnostics.add(it) },
        )
        return RunningAgent(process, ProcessTree(process.process.toHandle()), diagnostics, transcript)
    }

    private companion object {
        const val FAKE_AGENT_CLASS = "com.singularity.todo.feature.ai.acp.FakeAcpAgentKt"

        const val MODE_GOOD = "good"
        const val MODE_BAD_VERSION = "bad-version"
        const val MODE_CRASH = "crash"
        const val MODE_SILENT = "silent"
        const val MODE_MUTE_CLOSE = "mute-close"
    }
}
