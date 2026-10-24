package com.singularity.todo.feature.ai.acp

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.process.ProcessTree
import com.singularity.todo.core.process.Subprocess
import com.singularity.todo.test.helpers.awaitState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * ## The lifecycle
 *
 * The last two tests are about the client's own transitions rather than the pipe:
 * a close that lands while a turn is running, and a close that lands while a
 * connect is still waiting for the agent. Both are races, and a race that has no
 * test is a race that comes back. They are here, in this file, because they need a
 * real agent on the other end of the pipe — the agent that does not answer the
 * handshake, and the turn that is still running when the screen goes away.
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

        awaitExit(agent.process.process)
        assertTrue(
            !agent.process.process.isAlive,
            "the agent's process is gone even though it never answered the goodbye",
        )
    }

    @Test
    fun `a close that lands during a turn ends the turn and leaves the client closed`() = runTest {
        val agent = startAgent(MODE_GOOD)

        val client = AcpClient(
            scope = this,
            process = agent.process,
            tree = agent.tree,
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
        )
        withContext(Dispatchers.IO) { client.connect() }

        // The turn is held open on purpose. The collector stops at the agent's first
        // message and waits for the test to let it go, so the close below lands
        // while the turn is still running.
        //
        // Nothing here sleeps to arrange that: the release is what ends the turn,
        // and the test decides when. A turn that was over before the close arrived
        // would test nothing, and a turn that waited for a timer would test the
        // timer.
        val answered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<AcpTurnEvent>()
        val turn = async {
            // The failure is caught inside the coroutine, and that is not a detail:
            // an exception that leaves an `async` cancels the scope it was started
            // in, and a cancelled scope cannot finish the test it is in.
            //
            // What the turn ends with is the protocol's business — the transport
            // goes under it, and the flow ends with the transport. That the turn
            // ends is this test's.
            runCatchingCancellable {
                client.prompt("hello").collect { event ->
                    events += event
                    answered.complete(Unit)
                    release.await()
                }
            }
        }

        answered.await()
        withContext(Dispatchers.IO) { client.close() }
        release.complete(Unit)

        val ended = turn.await()

        assertTrue(
            events.isNotEmpty(),
            "the agent's answer arrived before the close, so the turn really was in flight",
        )
        assertEquals(
            "closed",
            stateOf(client),
            "the client is closed, not half-updated",
        )
        awaitExit(agent.process.process)
        assertTrue(
            !agent.process.process.isAlive,
            "the agent's process is gone: $ended",
        )
    }

    @Test
    fun `a close that lands during a connect cuts it short, and the client ends closed`() = runTest {
        val agent = startAgent(MODE_SILENT)

        val client = AcpClient(
            scope = this,
            process = agent.process,
            tree = agent.tree,
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
            connectTimeout = 2.seconds,
        )

        // Both transitions run on the test's own dispatcher, and that is what makes
        // the order certain rather than likely: the connect is queued first and
        // holds the client's lifecycle lock while it waits for an answer that will
        // not come, so the close is asked for while the connect is in flight.
        //
        // The deadline is on the virtual clock here, so the wait is instant.
        //
        // The failure is caught rather than thrown out of the coroutine: an
        // exception that leaves an `async` is reported to the test scope, and a
        // failed scope cancels the very close this test is about.
        val connect = async {
            try {
                client.connect()
                null
            } catch (e: AgentHandshakeTimeout) {
                e
            } catch (e: AgentUnavailable) {
                e
            }
        }
        val close = async { client.close() }

        // The close ends the handshake rather than waiting for it: the connect fails
        // with "the agent stopped", not with its own deadline. A close that waited
        // out the handshake would leave the reader looking at a screen that says
        // "connecting" for as long as the agent takes to answer — a minute, if the
        // agent is an `npx` invocation downloading its package.
        //
        // So the guard is not "the close wins the race"; it is "the close ends the
        // handshake and the fields are cleared once, by one of them". Which of the
        // two fails the connect is the protocol's business.
        val failure = connect.await()
        assertTrue(
            failure is AgentUnavailable,
            "the close ended the handshake instead of waiting out its deadline: $failure",
        )
        close.await()

        assertEquals(
            "closed",
            stateOf(client),
            "the client is closed, not half-updated",
        )
    }

    /**
     * The close that lands *before* the connect, rather than during it.
     *
     * This is the other order the two transitions can take, and it reaches a
     * different line: the connect finds the conversation already over before it
     * has a handshake to cut short, so it gives up as soon as it has built its
     * transport.
     *
     * What it has to leave behind is nothing. The client promises that a
     * connection which did not happen leaves nothing behind — no transport with
     * a reader still running on it, no protocol, no client — and the transport
     * this connect builds is the thing that has to be given back.
     */
    @Test
    fun `a close that lands before the connect leaves the client closed`() = runTest {
        val agent = startAgent(MODE_SILENT)

        val client = AcpClient(
            scope = this,
            process = agent.process,
            tree = agent.tree,
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
            connectTimeout = 2.seconds,
        )

        // The close comes first, and it finishes: there is no conversation left to
        // close afterwards, so the connect below is the connect of a client that
        // has already been closed.
        //
        // On the IO dispatcher, like the other close: closing the agent's process
        // waits for it to go, and that is blocking work.
        withContext(Dispatchers.IO) { client.close() }

        val failure = runCatching { client.connect() }.exceptionOrNull()
        assertTrue(
            failure is AgentUnavailable,
            "the connect noticed that the conversation was over: $failure",
        )
        assertEquals(
            "The conversation ended before the agent answered.",
            failure?.message,
            "the connect failed because the conversation was already over, and not because the " +
                "agent went quiet — the other branch of this failure says something else",
        )
        assertEquals(
            "closed",
            stateOf(client),
            "the client is closed, not half-updated",
        )
        awaitExit(agent.process.process)
        assertTrue(
            !agent.process.process.isAlive,
            "the agent's process is gone",
        )
    }

    /**
     * The terminate that lands during a close.
     *
     * A terminate is the one transition that cannot wait: the screen is going away
     * and there is nowhere left to wait in. So it *tries* the lock rather than
     * taking it, and a terminate that lands while a close holds the lock has to end
     * the conversation anyway — and leave the client in one state, not half of one.
     *
     * The close here is the slow one. The agent answers everything but the goodbye,
     * so the close holds the lock while it waits for an answer that is not coming,
     * and that window is where the terminate lands.
     */
    @Test
    fun `a terminate that lands during a close ends the conversation`() = runTest {
        val agent = startAgent(MODE_MUTE_CLOSE)

        val client = AcpClient(
            scope = this,
            process = agent.process,
            tree = agent.tree,
            operations = AcpClientSessionOperations { PermissionDecision.Declined },
            toolServer = null,
            closeTimeout = 30.seconds,
        )
        withContext(Dispatchers.IO) { client.connect() }
        assertEquals("connected", stateOf(client), "the agent answered the handshake")

        // The close runs on the IO dispatcher: the goodbye it waits for is answered
        // by a real process, and a real process answers on a real clock.
        val close = async(Dispatchers.IO) { client.close() }

        // The agent has received the goodbye and is not answering it, so the close
        // is holding the lock — which is the state the terminate has to cope with.
        awaitState { agent.transcript.readText().contains("session/close") }

        // The terminate cannot take the lock — the close has it — and it does not
        // wait for it either. It ends the conversation anyway: the tree and the
        // pipes go, the goodbye fails, and the close's teardown finishes the job.
        client.terminate()
        close.await()

        assertEquals(
            "closed",
            stateOf(client),
            "the client is closed, not half-updated",
        )
        awaitExit(agent.process.process)
        assertTrue(
            !agent.process.process.isAlive,
            "the agent's process is gone",
        )
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    /**
     * The client's state, as a caller can see it.
     *
     * The client does not publish its fields, so its state is read the way a caller
     * reads it: by asking for a turn. A connected client hands back a flow; a closed
     * one says it is not connected. Anything else — a flow that fails when it is
     * collected, an exception from nowhere — is the half-updated state the client's
     * lifecycle guard exists to prevent.
     */
    private fun stateOf(client: AcpClient): String = runCatching { client.prompt("probe") }.fold(
        onSuccess = { "connected" },
        onFailure = { failure ->
            if (failure is IllegalStateException && failure.message == "The client is not connected") {
                "closed"
            } else {
                "half-updated: $failure"
            }
        },
    )

    /**
     * Waits for the agent's process to be gone.
     *
     * The tree's grace period is measured on the test's virtual clock, and the
     * operating system's reaping of a killed process is not: a process that has
     * been killed has to die in real time, and a machine under load takes longer
     * at it. So the test waits for the exit and then asks, rather than asking
     * straight away and hoping the machine was idle.
     */
    private fun awaitExit(process: java.lang.Process) {
        runCatching { process.onExit().get(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }

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

        /** How long a real process is given to die before the test gives up on it. */
        const val EXIT_TIMEOUT_SECONDS = 10L

        const val MODE_GOOD = "good"
        const val MODE_BAD_VERSION = "bad-version"
        const val MODE_CRASH = "crash"
        const val MODE_SILENT = "silent"
        const val MODE_MUTE_CLOSE = "mute-close"
    }
}
