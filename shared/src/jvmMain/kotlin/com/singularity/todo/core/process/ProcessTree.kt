package com.singularity.todo.core.process

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Owns a spawned process and everything it started.
 *
 * ## Why the tree and not the process
 *
 * An external agent is a process that starts processes. The agent spawns the tool server
 * this application hands it, and the agent may spawn helpers of its own. Terminating the
 * agent alone leaves those behind: the tool server keeps a database open, and a helper
 * keeps running, and the user has closed a conversation and still has a stray process
 * holding their data.
 *
 * So closing a session ends the *tree*: the agent, the tool server it started, and
 * anything below them.
 *
 * ## Order: deepest first
 *
 * A parent that is destroyed before its children can spawn replacements in the moment
 * between the signal and the death — and a child that outlives its parent is exactly the
 * leak this class exists to prevent. So the deepest descendants go first, and the parent
 * goes last.
 *
 * ## Two signals, then force
 *
 * The first signal asks politely, because a child that is halfway through writing a
 * record deserves to finish. The grace period is short enough that a closing session
 * does not hang, and long enough that a well-behaved child exits on its own. Whatever
 * survives the grace period is destroyed forcibly.
 *
 * ## Idempotent
 *
 * Closing twice is not an error. The screen's `onCleared` and the session's own teardown
 * both reach for this, and the order in which they arrive is not something to depend on.
 */
class ProcessTree(private val root: ProcessHandle, private val gracePeriod: Duration = DEFAULT_GRACE_PERIOD) {
    /**
     * Whether the graceful path has already run.
     *
     * The graceful path is [close], and it is the one that escalates to a force-kill.
     */
    private val closed = AtomicBoolean(false)

    /**
     * Whether the tree has already been asked to leave.
     *
     * This is a **separate** flag from [closed], and the separation is the point: the
     * urgent path ([destroyNow]) and the graceful path ([close]) are both reached for
     * the same tree, in whatever order the screen's teardown and the session's own
     * teardown arrive. One flag for both meant that the urgent path consumed the
     * graceful one's chance to run, so a tree that had been asked politely once
     * could never be force-killed — and an agent that ignores SIGTERM (one real
     * bridge does) outlived the session that ended it.
     */
    private val destroyed = AtomicBoolean(false)

    /**
     * Ends the tree: descendants first, then the process this tree owns.
     *
     * This escalates to a force-kill, and it does so whether or not [destroyNow] has
     * already asked the tree to leave. Asking and making sure are different acts: a
     * process that ignores the polite signal survives it, and only this path makes
     * sure it does not.
     *
     * @return how many processes were still alive when the tree was closed.
     */
    suspend fun close(): Int {
        if (!closed.compareAndSet(false, true)) return 0

        val descendants = descendantsDeepestFirst()
        val all = descendants + root

        // Ask politely, deepest first.
        all.forEach { handle -> runCatching { handle.destroy() } }

        // Give them the grace period to leave on their own.
        val survivors = awaitExit(all, gracePeriod)

        // Whatever is left is not leaving politely.
        survivors.forEach { handle -> runCatching { handle.destroyForcibly() } }

        // And the ones that were asked politely get a moment to actually die, so the
        // caller's next step does not race a dying process for the same file handle.
        if (survivors.isNotEmpty()) {
            awaitExit(survivors, KILL_GRACE_PERIOD)
        }

        return survivors.size
    }

    /**
     * Ends the tree without waiting for it.
     *
     * The destroys are issued immediately, deepest first. The escalation is not part of
     * this call — waiting needs a coroutine and the caller (a ViewModel being cleared)
     * has no scope left to wait in — but it is not *lost* either: a daemon thread carries
     * it, so a process that ignores the polite signal is still made to leave shortly
     * afterwards. Without that, the screen's teardown would leave the agent running: an
     * agent that ignores the polite signal (one real bridge does) would outlive the
     * session that ended it.
     *
     * This does **not** consume the graceful path's flag. A [close] that comes later
     * still runs, and it still escalates: this path asks the tree to leave, and the
     * graceful one is what makes leaving certain. The two are separate because the
     * order in which the screen's teardown and the session's teardown reach this
     * object is not something to depend on.
     */
    fun destroyNow() {
        if (!destroyed.compareAndSet(false, true)) return
        val all = descendantsDeepestFirst() + root
        all.forEach { handle -> runCatching { handle.destroy() } }
        escalateLater(all)
    }

    /**
     * Makes the tree leave, shortly, without waiting for it here.
     *
     * A thread rather than a coroutine because the escalation has to outlive whatever
     * coroutine asked for the tree to end: the caller is being cleared, and a
     * coroutine launched there would never run.
     */
    private fun escalateLater(all: List<ProcessHandle>) {
        Thread({
            runCatching {
                Thread.sleep(gracePeriod.inWholeMilliseconds)
                all.filter { it.isAlive }.forEach { handle -> runCatching { handle.destroyForcibly() } }
            }
        }, "process-tree-escalation").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The descendants of the root, deepest first.
     *
     * The depth is measured by walking each descendant's parents up to the root, because
     * the operating system's descendant set is flat: it says *who* descends from the
     * root, not *how far down* they are, and "deepest first" needs the distance.
     */
    private fun descendantsDeepestFirst(): List<ProcessHandle> =
        root.descendants()
            .toList()
            .map { handle -> handle to depthOf(handle) }
            .sortedByDescending { (_, depth) -> depth }
            .map { (handle, _) -> handle }

    private fun depthOf(handle: ProcessHandle): Int {
        var depth = 0
        var current: ProcessHandle? = handle.parent().orElse(null)
        while (current != null && current != root && depth < MAX_DEPTH) {
            depth++
            current = current.parent().orElse(null)
        }
        return depth
    }

    /** Waits up to [limit] for every handle to die, and returns those that did not. */
    private suspend fun awaitExit(handles: List<ProcessHandle>, limit: Duration): List<ProcessHandle> {
        if (handles.isEmpty()) return emptyList()
        val deadline = limit.inWholeMilliseconds
        var waited = 0L
        while (waited < deadline) {
            val alive = handles.filter { it.isAlive }
            if (alive.isEmpty()) return emptyList()
            delay(POLL_INTERVAL.inWholeMilliseconds)
            waited += POLL_INTERVAL.inWholeMilliseconds
        }
        return handles.filter { it.isAlive }
    }

    companion object {
        /** How long a child has to leave on its own after being asked. */
        val DEFAULT_GRACE_PERIOD: Duration = 2.seconds

        /** How long a forcibly destroyed process is given to actually die. */
        val KILL_GRACE_PERIOD: Duration = 500.milliseconds

        /** How often the tree checks whether its members are gone. */
        val POLL_INTERVAL: Duration = 50.milliseconds

        /** A guard against a pathological parent chain. */
        private const val MAX_DEPTH = 64
    }
}
