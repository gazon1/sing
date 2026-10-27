package com.singularity.todo.core.process

import co.touchlab.kermit.Logger
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * A child process with both directions open.
 *
 * This is what a line protocol needs: the application writes requests to the child's
 * standard input and reads responses from its standard output, for as long as the
 * conversation lasts.
 *
 * ## Standard error is not the protocol
 *
 * The child's standard error is drained to its own sink and is never folded into the
 * protocol stream. A protocol is a byte stream with a grammar; anything else on it is a
 * parse error waiting for a message that happens to contain a newline. Folding the two
 * together is how a chatty agent turns every diagnostic into a broken frame.
 *
 * ## No read end is closed early
 *
 * The defect this file's KDoc tells at length about — closing a child's read end right
 * after `start()`, which delivers SIGPIPE and kills the child with exit 141 — is the
 * reason the streams here live as long as the process does. [close] is the only thing
 * that closes them, and it closes them *after* the child is gone.
 */
class DuplexProcess internal constructor(
    /** The child. Its handle is what the process tree terminates. */
    val process: Process,
    private val stdin: OutputStream,
    private val stdout: InputStream,
) {
    private val closed = AtomicBoolean(false)

    /**
     * Writes one line to the child's standard input.
     *
     * The newline is part of the line: the protocol is line-delimited, and a line
     * without its terminator is a request the child never sees.
     */
    fun writeLine(line: String) {
        stdin.write((line + "\n").toByteArray())
        stdin.flush()
    }

    /**
     * The child's standard output, line by line, until the stream ends.
     *
     * The read happens on the IO dispatcher; the flow ends when the child closes its
     * output, which is how a child says it is finished.
     *
     * ## Why a closed stream is an end and not a failure
     *
     * The read blocks, and a blocking read cannot be interrupted: when the caller
     * closes the process, the pipe's read end is closed underneath the reader, and the
     * read fails rather than returning. That failure is the *same fact* as the end of
     * the stream — the conversation with the child is over — and it is reported as an
     * error only because the API says so. So it is caught here, and the flow ends the
     * way it ends when the child closes its output.
     *
     * Without this, closing a session leaves a coroutine blocked in a read that only a
     * signal can end, and a test that closes a session waits for a coroutine that will
     * never finish.
     */
    fun stdoutLines(): Flow<String> = flow<String> {
        val reader = InputStreamReader(stdout, Charsets.UTF_8)
        try {
            readCappedLines(reader, 1_048_576) { emit(it) }
        } finally {
            runCatching { reader.close() }
        }
    }.flowOn(Dispatchers.IO)


    /**
     * Waits for the child to exit, up to [timeoutMillis].
     *
     * @return the exit code, or null when the child was still running when the time
     *   ran out. A child that will not exit is a fact the caller has to handle, not an
     *   exception.
     */
    fun awaitExit(timeoutMillis: Long): Int? {
        val exited = process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        return if (exited) process.exitValue() else null
    }

    /**
     * Closes the pipes.
     *
     * Idempotent, and it closes the child's standard input *first*: that is how the
     * child learns the conversation is over, and a child that waits for input would
     * otherwise keep the pipes open until it is destroyed.
     *
     * The **process** is not closed here. It is ended by the process tree, which owns
     * it and everything it started — a child that is still writing to a closed pipe
     * would be killed by a signal it never saw coming.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stdin.close() }
        runCatching { stdout.close() }
    }

    internal fun closeQuietly() = close()
}

/**
 * Runs a subprocess for its exit code alone.
 *
 * ## Why this is one shared function
 *
 * It existed as two copies — one in `JvmNotifier`, one in `JvmReminderScheduler` — and
 * both were wrong in the same way. Anything this file does is a decision about how this
 * process talks to the operating system, and it should not be a decision two files can
 * make differently.
 *
 * ## Why there is no `close()` anywhere in here
 *
 * The first version drained the child by closing its streams immediately after `start()`:
 *
 * ```kotlin
 * val process = ProcessBuilder(argv).redirectErrorStream(true).start()
 * process.inputStream.close()
 * process.errorStream.close()
 * process.waitFor()
 * ```
 *
 * That looks like tidiness and is in fact a race that loses. Closing the read end of a
 * pipe before the child has written to it delivers **SIGPIPE**, and the child dies with
 * exit code **141**. Whether the child writes first is a race it usually loses:
 *
 * ```
 * $ java Probe      # notify-send --version, streams closed right after start()
 * exit=0
 * exit=141
 * exit=141
 * exit=141
 * ```
 *
 * ## Why that was not a cosmetic bug
 *
 * These exit codes are not logged and discarded — they are *capabilities*. `notify-send
 * --version` deciding the host can display notifications, and `systemd-run --user
 * --version` deciding the host can schedule any, are the two facts
 * `JvmReminderScheduler.isSupported` is built from. A 141 turns both into `false`, and
 * `false` means **Desktop reminders refuse to arm at all** — no error, no unit, no
 * notification, and a UI that correctly believes the platform has no scheduler.
 *
 * Every test passed, because every test injected a recording runner in place of this one.
 * The bug lives exclusively in the default that no test reached.
 *
 * ## Why `Redirect.DISCARD` rather than draining
 *
 * Discarding to the null device removes the pipe, so there is nothing left to close and
 * nothing left to race. It is also cheaper than reading megabytes of output nobody wants,
 * and it does not block on a child that writes more than a pipe buffer would hold.
 */
object Subprocess {

    /**
     * Run [argv] and return its exit code, or `-1` if it could not be started.
     *
     * `-1` rather than a throw: every caller is asking "did this work?", and a missing
     * binary is an answer, not an exception.
     *
     * [argv] is a list and is never joined into a shell string. Every caller passes
     * user-authored text through it — a task title, a reminder id — and a shell would give
     * that text a way out of being data.
     */
    fun runQuietly(argv: List<String>): Int = runCatching {
        ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()
    }.getOrDefault(-1)

    /**
     * Run [argv] and return its standard output, or null when it could not be started or
     * exited non-zero.
     *
     * [stdin] is written to the child and then closed, for commands that take input —
     * `secret-tool store` reads the secret from stdin rather than the command line, which
     * is the right choice for a secret and the reason this parameter exists.
     *
     * ## Why this exists rather than callers reading `inputStream` themselves
     *
     * Three reasons, and the first is a latent deadlock.
     *
     * **Draining is mandatory, and only this function does it.** A caller that starts a
     * process and calls `waitFor()` without reading its output deadlocks the moment the
     * child writes more than a pipe buffer holds — 64 KiB on Linux. `JvmSecureStorage` had
     * exactly that shape in two of its four methods: no drain, no close, just `waitFor()`.
     * They work today because `which` and `secret-tool delete` write a few bytes, and they
     * would hang the day that changed. The hang is not the kind anyone debugs quickly,
     * because it appears on a machine where "this always works".
     *
     * **Standard error is folded into standard output.** A caller capturing stdout must
     * also consume stderr, or a chatty child fills the stderr pipe and blocks the same
     * way.
     *
     * **Secrets stay in one place.** `secret-tool lookup` returns a key. Routing that
     * through a shared, tested function means exactly one line in this repository reads a
     * subprocess's output, and it can be reviewed as such.
     *
     * Null covers both failure modes — not started, and started but failed — because
     * every caller wants "no usable value", not the distinction.
     *
     * [workingDirectory] runs the command somewhere else — a `git` that has to run in a
     * checkout, for instance, which cannot be expressed as an argument to `git` itself.
     */
    fun runCapturing(argv: List<String>, stdin: String? = null, workingDirectory: File? = null): String? = runCatching {
        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        if (workingDirectory != null) builder.directory(workingDirectory)
        val process = builder.start()

        // Close stdin whatever happens. A child that reads stdin to EOF and never gets it
        // waits forever, and `waitFor()` below would wait right along with it.
        runCatching {
            process.outputStream.use { out ->
                stdin?.let { out.write(it.toByteArray()) }
            }
        }

        // Read to EOF *before* waiting. Reversing these two is the deadlock above.
        val maxOutput = 16 * 1024 * 1024
        val buf = CharArray(8192)
        val out = StringBuilder()
        InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
            while (true) {
                val n = reader.read(buf)
                if (n == -1) break
                if (out.length + n > maxOutput) {
                    out.append(buf, 0, (maxOutput - out.length).coerceAtLeast(0))
                    subprocessLog.w { "subprocess output truncated at $maxOutput bytes" }
                    break
                }
                out.append(buf, 0, n)
            }
        }
        if (process.waitFor() != 0) null else out.toString()
    }.getOrNull()

    /**
     * Starts [argv] with both directions open.
     *
     * The child's standard output is the protocol stream, its standard input is where
     * requests go, and its standard error is drained to [stderrSink] on a thread of its
     * own — never into the protocol stream.
     *
     * ## Why standard error is drained rather than ignored
     *
     * A child that writes to standard error and nobody reads it blocks. The pipe buffer
     * is finite (64 KiB on Linux), and an agent that logs a stack trace while it starts
     * up is enough to fill it. The child then waits for a reader that never comes, and
     * the protocol sees a process that started and said nothing.
     *
     * Redirecting it to the null device would avoid the block and lose the diagnostics,
     * and the diagnostics are the difference between "the agent refused to start" and
     * "the agent printed the reason it refused to start" — which is the difference
     * between a support question and a bug report.
     *
     * ## Why it is not folded into the protocol stream
     *
     * The protocol stream is a line-delimited byte stream with a grammar. Diagnostics on
     * it are parse errors, and they arrive at the worst possible moment: in the middle
     * of a frame the application is trying to read.
     *
     * @param workingDirectory the child's working directory, or null to inherit ours.
     * @param stderrSink receives the child's standard error, line by line.
     * @throws java.io.IOException if the child could not be started.
     */
    fun spawnDuplex(
        argv: List<String>,
        workingDirectory: File? = null,
        stderrSink: (String) -> Unit = {},
    ): DuplexProcess {
        val builder = ProcessBuilder(argv).redirectErrorStream(false)
        if (workingDirectory != null) builder.directory(workingDirectory)
        val process = builder.start()
        drainStderr(process.errorStream, stderrSink)
        return DuplexProcess(
            process = process,
            stdin = process.outputStream,
            stdout = process.inputStream,
        )
    }

    /**
     * Reads the child's standard error on a daemon thread until it ends.
     *
     * A thread rather than a coroutine because the drain has to outlive whatever
     * coroutine started the process and must not be cancelled by it: the child is still
     * writing, and a cancelled drain is an unread pipe.
     */
    private fun drainStderr(stream: InputStream, sink: (String) -> Unit) {
        Thread({
            runCatching {
                val reader = InputStreamReader(stream, Charsets.UTF_8)
                kotlinx.coroutines.runBlocking<Unit> {
                    readCappedLines(reader, 1_048_576) { sink(it) }
                }
                runCatching { reader.close() }
            }
        }, "subprocess-stderr-drain").apply {
            isDaemon = true
            start()
        }
    }
}

private val subprocessLog = co.touchlab.kermit.Logger.withTag("subprocess")

private suspend fun readCappedLines(reader: java.io.Reader, maxLine: Int, emit: suspend (String) -> Unit) {
    val buf = CharArray(8192)
    val line = StringBuilder()
    while (true) {
        val n = try {
            reader.read(buf)
        } catch (e: IOException) {
            subprocessLog.d(e) { "The child's output ended while it was being read." }
            -1
        }
        if (n == -1) break
        for (i in 0 until n) {
            val c = buf[i]
            if (c == '\n') {
                emit(line.toString())
                line.setLength(0)
            } else if (line.length >= maxLine) {
                emit(line.toString())
                line.setLength(0)
                if (c != '\r') line.append(c)
            } else {
                line.append(c)
            }
        }
    }
    if (line.isNotEmpty()) emit(line.toString())
}
