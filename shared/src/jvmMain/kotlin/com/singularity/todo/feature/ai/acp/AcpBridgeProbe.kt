package com.singularity.todo.feature.ai.acp

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.process.DuplexProcess
import com.singularity.todo.core.process.ProcessTree
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The method of a JSON-RPC frame, with its quotes. */
private val METHOD = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"")

/** The patience of a probe that may be downloading the bridge's package. */
private val BRIDGE_TIMEOUT: Duration = 3.minutes

/**
 * The application's client, against a real agent bridge.
 *
 * ## What this is for
 *
 * The tests talk to a fake agent: ours, taught to answer what our client expects.
 * That proves the client and the fake agree with each other. It says nothing about
 * whether they agree with the *protocol* — and the protocol is written by somebody
 * else. A bridge is that somebody else: it answers what the protocol says, not what
 * we taught our fake to answer.
 *
 * So this drives the same client the screen drives, against a real bridge, and
 * reports what came back. The interesting part is the difference: a notification our
 * adapter has never seen, a capability shape our model does not have, a field the
 * bridge sends that our fake does not.
 *
 * ## Why it is a program and not a test
 *
 * It needs a real `npx`, a real network and a real bridge. A test that cannot run is
 * a skipped test, and this repository does not allow skipped tests — a class that no
 * population selects is a class that stops running, silently. So this is a program,
 * run by hand through `scripts/acp-bridge-probe.sh`, and its verdict is a line of
 * output rather than a green tick.
 *
 * ## What it does not do
 *
 * It does not prompt the agent. A prompt needs the agent behind the bridge to be
 * installed and signed in, and this probe says so rather than pretending: the
 * handshake and the session are the protocol's own layers, and they are what the
 * application owns.
 */
object AcpBridgeProbe {

    /**
     * What the probe found.
     *
     * @param connected whether the client completed the handshake and opened a session.
     * @param agentName the agent's own name, as it announced it.
     * @param agentVersion the agent's own version, as it announced it.
     * @param failure why the connect failed, when it did.
     * @param methods the methods the bridge used, in order, with how often each came.
     * @param transcript every frame the bridge sent, verbatim.
     */
    data class Report(
        val connected: Boolean,
        val agentName: String,
        val agentVersion: String,
        val failure: String?,
        val methods: List<Pair<String, Int>>,
        val transcript: List<String>,
    )

    /**
     * Runs the client against the bridge and reports what happened.
     *
     * @param command the bridge's command line — the program and its arguments.
     * @param workingDirectory the directory the session is opened in.
     */
    suspend fun run(command: List<String>, workingDirectory: String): Report {
        val transcript = Transcript()
        val child = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .directory(File(workingDirectory))
            .start()
        val process = DuplexProcess(child, child.outputStream, transcript.tee(child.inputStream))
        val tree = ProcessTree(child.toHandle())
        val scope = CoroutineScope(SupervisorJob())

        return try {
            val client = AcpClient(
                scope = scope,
                process = process,
                tree = tree,
                operations = AcpClientSessionOperations { PermissionDecision.Declined },
                toolServer = null,
                workingDirectory = workingDirectory,
                // The bridge may be an `npx` invocation that has never run on this
                // machine, and the download is not the handshake. The deadline here is
                // the probe's patience, not the client's contract.
                connectTimeout = BRIDGE_TIMEOUT,
            )
            try {
                runCatchingCancellable { client.connect() }.fold(
                    onSuccess = { info ->
                        Report(
                            connected = true,
                            agentName = info.name,
                            agentVersion = info.version,
                            failure = null,
                            methods = transcript.methods(),
                            transcript = transcript.frames(),
                        )
                    },
                    onFailure = { failure ->
                        Report(
                            connected = false,
                            agentName = "",
                            agentVersion = "",
                            failure = failure.message ?: failure::class.simpleName.orEmpty(),
                            methods = transcript.methods(),
                            transcript = transcript.frames(),
                        )
                    },
                )
            } finally {
                client.close()
            }
        } finally {
            scope.cancel()
            tree.close()
        }
    }

    /**
     * The bridge's frames, as they arrived.
     *
     * The transcript is a copy of the bytes, not a second reader: the protocol owns
     * the stream, and the copy is taken underneath it, so the two never race for a
     * line.
     */
    private class Transcript {
        private val buffer = StringBuilder()

        fun tee(source: InputStream): InputStream = object : FilterInputStream(source) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val read = super.read(bytes, offset, length)
                if (read > 0) {
                    synchronized(buffer) {
                        buffer.append(String(bytes, offset, read, Charsets.UTF_8))
                    }
                }
                return read
            }
        }

        fun frames(): List<String> = synchronized(buffer) {
            buffer.toString().lines().filter { it.isNotBlank() }
        }

        /**
         * The methods the bridge used, and how often each came.
         *
         * A notification the client has never seen is the thing this probe is for,
         * and a method is the first thing a reader wants to know about a frame.
         */
        fun methods(): List<Pair<String, Int>> {
            val counts = LinkedHashMap<String, Int>()
            for (frame in frames()) {
                val method = METHOD.find(frame)?.groupValues?.get(1) ?: continue
                counts[method] = (counts[method] ?: 0) + 1
            }
            return counts.toList()
        }
    }
}

/**
 * Runs the probe and prints its report.
 *
 * The first argument is the directory the session is opened in; the rest is the
 * bridge's command line. The exit code is the verdict: zero when the client
 * completed the handshake against the real bridge, one when it did not. A script
 * that runs this wants the answer, not a report nobody reads.
 *
 * A program's entry point has to wait for the program: there is no scope to launch
 * into, and the caller is a shell.
 */
@Suppress("NoRunBlocking")
fun main(args: Array<String>) {
    val workingDirectory = args.firstOrNull() ?: System.getProperty("user.dir") ?: "."
    val command = args.drop(1).ifEmpty {
        listOf("npx", "-y", "@agentclientprotocol/claude-agent-acp")
    }
    val report = runBlocking { AcpBridgeProbe.run(command, workingDirectory) }

    println("bridge: ${command.joinToString(" ")}")
    println("working directory: $workingDirectory")
    println("connected: ${report.connected}")
    if (report.connected) {
        println("agent: ${report.agentName} ${report.agentVersion}")
    } else {
        println("failure: ${report.failure}")
    }
    println("methods the bridge used:")
    for ((method, count) in report.methods) {
        println("  $method ($count)")
    }
    println("frames:")
    for (frame in report.transcript) {
        println("  $frame")
    }

    if (!report.connected) {
        kotlin.system.exitProcess(1)
    }
}
