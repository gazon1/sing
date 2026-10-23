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
    private val closed = AtomicBoolean(false)

    /**
     * Ends the tree: descendants first, then the process this tree owns.
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
     * The destroys are issued immediately, deepest first; the force-kill escalation is
     * not, because waiting needs a coroutine and the caller — a ViewModel being
     * cleared — has no scope left to wait in.
     *
     * This is the last line of defence, not the graceful path. A process that ignores
     * the polite signal survives it; the graceful [close] is what handles those, and it
     * is the path a disconnect takes.
     */
    fun destroyNow() {
        if (!closed.compareAndSet(false, true)) return
        (descendantsDeepestFirst() + root).forEach { handle ->
            runCatching { handle.destroy() }
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
