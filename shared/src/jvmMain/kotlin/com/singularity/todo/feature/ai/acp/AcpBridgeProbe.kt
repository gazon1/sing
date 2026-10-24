package com.singularity.todo.feature.ai.acp

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.process.DuplexProcess
import com.singularity.todo.core.process.ProcessTree
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The method of a JSON-RPC frame, with its quotes. */
private val METHOD = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"")

/** The patience of a probe that may be downloading the bridge's package. */
private val BRIDGE_TIMEOUT: Duration = 3.minutes

/** How long the bridge may stay silent before the transcript is considered complete. */
private val QUIET_PERIOD: Duration = 2.seconds

/**
 * How long the probe waits before it may accept the bridge as quiet.
 *
 * A quiet period alone is not a guarantee: the bridge announces its available commands
 * on a timer, and a timer longer than the quiet period would put the frame after the
 * snapshot — every time, not sometimes. So the probe waits at least this long after
 * the connect, and then waits for the quiet on top of it. The boundary of the promise
 * is the limit below: a bridge that announces something later than that is outside
 * what this probe can promise, and the records say so rather than the probe pretending
 * otherwise.
 */
private val MIN_WAIT: Duration = 10.seconds

/** How long the probe waits for the bridge to go quiet, whatever it does. */
private val QUIET_LIMIT: Duration = 30.seconds

/** How often the probe looks at the transcript while it waits. */
private val POLL_INTERVAL: Duration = 100.milliseconds

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
                val verdict = runCatchingCancellable { client.connect() }.fold(
                    onSuccess = { info ->
                        Report(
                            connected = true,
                            agentName = info.name,
                            agentVersion = info.version,
                            failure = null,
                            methods = emptyList(),
                            transcript = emptyList(),
                        )
                    },
                    onFailure = { failure ->
                        Report(
                            connected = false,
                            agentName = "",
                            agentVersion = "",
                            failure = failure.message ?: failure::class.simpleName.orEmpty(),
                            methods = emptyList(),
                            transcript = emptyList(),
                        )
                    },
                )
                // The bridge keeps talking after the session it answered: its
                // available commands come on a timer, and a transcript read the moment
                // the connect returned would miss that frame — which is one of the
                // frames this probe is for. The authentication notification is a
                // different case: the bridge sends it when its own authentication probe
                // resolves, and in the recorded run that happened during the handshake,
                // before the session answer. So the transcript is read when the bridge
                // has stopped talking, or when the wait runs out.
                transcript.awaitQuiet(QUIET_PERIOD, QUIET_LIMIT, MIN_WAIT)
                verdict.copy(
                    methods = transcript.methods(),
                    transcript = transcript.frames(),
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
     *
     * The copy is a copy of *bytes*: they are accumulated and decoded once, at the
     * end. Decoding each chunk on its own would cut a multi-byte character in half
     * whenever a chunk boundary fell inside one, and the transcript would carry a
     * replacement character where the bridge had written an em dash.
     */
    private class Transcript {
        private val buffer = ByteArrayOutputStream()
        private var chunks = 0

        fun tee(source: InputStream): InputStream = object : FilterInputStream(source) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val read = super.read(bytes, offset, length)
                if (read > 0) copy(bytes, offset, read)
                return read
            }

            // The single-byte read does not go through the bulk one: a reader that
            // asks for one byte at a time would bypass the copy, and the transcript
            // would quietly lose whatever it missed.
            override fun read(): Int {
                val read = super.read()
                if (read >= 0) copy(byteArrayOf(read.toByte()), 0, 1)
                return read
            }

            private fun copy(bytes: ByteArray, offset: Int, length: Int) {
                synchronized(buffer) {
                    buffer.write(bytes, offset, length)
                    chunks++
                }
            }
        }

        fun frames(): List<String> = synchronized(buffer) {
            String(buffer.toByteArray(), Charsets.UTF_8).lines().filter { it.isNotBlank() }
        }

        /**
         * Waits until the bridge stops talking.
         *
         * The quiet period is measured on the clock, not on the frames: a bridge that
         * says nothing at all must not make the probe wait for the limit. And the
         * limit is there so that a bridge that never goes quiet does not make the
         * probe wait for ever.
         *
         * The minimum is there for the other side of the same promise: a bridge that
         * announces something on a timer longer than the quiet period would be
         * snapshotted before the announcement, every time. So the probe waits at least
         * that long before it may accept the quiet, and the limit is where the promise
         * ends.
         */
        suspend fun awaitQuiet(quiet: Duration, limit: Duration, minimum: Duration) {
            val started = TimeSource.Monotonic.markNow()
            var seen = chunks()
            var quietSince = TimeSource.Monotonic.markNow()
            while (started.elapsedNow() < limit) {
                delay(POLL_INTERVAL)
                val now = chunks()
                if (now != seen) {
                    seen = now
                    quietSince = TimeSource.Monotonic.markNow()
                } else if (quietSince.elapsedNow() >= quiet && started.elapsedNow() >= minimum) {
                    return
                }
            }
        }

        private fun chunks(): Int = synchronized(buffer) { chunks }

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
