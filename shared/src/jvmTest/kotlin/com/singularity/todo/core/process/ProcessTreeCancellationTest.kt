@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The process tree's error and cancellation paths, against a **fake** process handle.
 *
 * ## Why a fake and not a real process
 *
 * [ProcessTreeTest] spawns real processes, because "the tree leaves no process
 * behind" is a fact about the operating system and only the operating system can
 * show it. The two questions this class answers are not about the operating
 * system:
 *
 * - a `destroy()` that **fails** does not stop the rest of the tree from being
 *   ended, and
 * - a **cancelled** close stops where it is, and says so.
 *
 * Neither can be produced with a real process on purpose: `ProcessHandle.destroy()`
 * does not fail on demand, and a real process dies on its own schedule rather than
 * when the test would like it to. A fake makes both reachable.
 *
 * ## What the fake is allowed to do
 *
 * `java.lang.ProcessHandle` is an interface with no default methods, so a fake is
 * a class that implements it — the whole surface, nothing more. It records what it
 * was asked to do and answers [isAlive] from its own state, so the tree's own
 * logic — which processes are asked, in which order, and what happens to the ones
 * that survive — is what the test exercises.
 *
 * The record lives on the test instance, not in a companion: this module runs its
 * test methods concurrently, so a shared log would interleave two trees' histories
 * into one list.
 */
@Tag("fast")
class ProcessTreeCancellationTest {
    /** The pids of the processes that were asked to leave, in order. */
    private val askedToLeave = mutableListOf<Long>()

    @Test
    fun `a destroy that fails does not stop the rest of the tree from being ended`() =
        runTest {
            val child = FakeHandle(pid = 2L, destroyFailure = IllegalStateException("the handle is gone"))
            val root = FakeHandle(pid = 1L, children = listOf(child))
            val tree = ProcessTree(root, gracePeriod = 100.milliseconds)

            tree.close()

            // The child was asked first and its destroy failed. That failure belongs
            // to the child: the parent is a different process, and a process that
            // could not be reached must not leave the rest of the tree running.
            assertTrue(child.destroyAttempted.get(), "the child was asked to leave")
            assertTrue(root.destroyAttempted.get(), "and so was the parent")
            assertFalse(root.isAlive, "the parent is gone")
        }

    @Test
    fun `a destroy that fails still leaves the survivor to be killed`() =
        runTest {
            val child = FakeHandle(pid = 2L, destroyFailure = IllegalStateException("the handle is gone"))
            val root = FakeHandle(pid = 1L, children = listOf(child))
            val tree = ProcessTree(root, gracePeriod = 100.milliseconds)

            tree.close()

            // The polite signal did not reach the child, so the child is still
            // standing when the grace period is over — and the escalation has to
            // reach it.
            assertTrue(child.destroyForciblyAttempted.get(), "the survivor was killed")
        }

    @Test
    fun `a cancellation while waiting for the tree to die propagates`() =
        runTest {
            val root = FakeHandle(pid = 1L, staysAlive = true)
            val tree = ProcessTree(root, gracePeriod = 10_000.milliseconds)

            val close = async { tree.close() }
            // The tree was asked to leave and is now waiting for the process to die.
            // That wait is on the operating system's clock, not the test's, so the
            // cancellation has to be delivered while it is in progress.
            runCurrent()
            assertTrue(root.destroyAttempted.get(), "the tree was asked to leave")

            close.cancel()

            // The cancellation is not swallowed into a normal return: the caller is
            // told the close did not finish.
            assertFailsWith<CancellationException> { close.await() }
            // The wait was cancelled, so the escalation never ran. That is the
            // documented consequence of cancelling a cleanup: it stops where it is.
            assertFalse(root.destroyForciblyAttempted.get(), "the escalation did not run")
        }

    @Test
    fun `a cancellation thrown by destroy is not turned into an ordinary failure`() =
        runTest {
            val root = FakeHandle(pid = 1L, destroyFailure = CancellationException("cancelled mid-destroy"))
            val tree = ProcessTree(root, gracePeriod = 100.milliseconds)

            // A cancellation is not a defect and not a failure to report — it is the
            // machinery withdrawing the work. `runCatching` would have turned it
            // into a `Result.failure` and carried on as though the tree had been
            // asked politely, which is the opposite of what was asked for.
            assertFailsWith<CancellationException> { tree.close() }
        }

    @Test
    fun `the tree asks the survivors to leave in the deepest-first order`() =
        runTest {
            val grandchild = FakeHandle(pid = 3L)
            val child = FakeHandle(pid = 2L, children = listOf(grandchild))
            val root = FakeHandle(pid = 1L, children = listOf(child))
            val tree = ProcessTree(root, gracePeriod = 100.milliseconds)

            tree.close()

            assertEquals(
                listOf(3L, 2L, 1L),
                askedToLeave.toList(),
                "the deepest descendant goes first, and the parent goes last",
            )
        }

    /**
     * A process handle that records what it was asked to do.
     *
     * An inner class, so that the record it writes lands on the test instance that
     * owns the tree — two trees running at once must not write into each other's
     * history.
     */
    private inner class FakeHandle(
        private val pid: Long,
        private val children: List<FakeHandle> = emptyList(),
        private val destroyFailure: Throwable? = null,
        /** Stays alive whatever is done to it — a process that outlives every signal. */
        private val staysAlive: Boolean = false,
    ) : ProcessHandle {
        val destroyAttempted = AtomicBoolean(false)
        val destroyForciblyAttempted = AtomicBoolean(false)

        /** The parent, set once the tree of handles is built. */
        private var parentHandle: FakeHandle? = null

        @Volatile
        private var alive = true

        init {
            children.forEach { it.parentHandle = this }
        }

        override fun pid(): Long = pid

        override fun parent(): Optional<ProcessHandle> = Optional.ofNullable(parentHandle)

        override fun children(): Stream<ProcessHandle> = children.stream().map { it as ProcessHandle }

        override fun descendants(): Stream<ProcessHandle> =
            children.stream().flatMap { child ->
                Stream.concat<ProcessHandle>(Stream.of(child), child.descendants())
            }

        override fun info(): ProcessHandle.Info = EmptyInfo

        override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture.completedFuture(this)

        override fun supportsNormalTermination(): Boolean = true

        override fun destroy(): Boolean {
            destroyAttempted.set(true)
            askedToLeave += pid
            destroyFailure?.let { throw it }
            if (!staysAlive) alive = false
            return true
        }

        override fun destroyForcibly(): Boolean {
            destroyForciblyAttempted.set(true)
            if (!staysAlive) alive = false
            return true
        }

        override fun isAlive(): Boolean = alive

        override fun compareTo(other: ProcessHandle): Int = pid.compareTo(other.pid())

        override fun toString(): String = "FakeHandle($pid)"
    }

    /** A [ProcessHandle.Info] that says nothing — the tree never reads it. */
    private object EmptyInfo : ProcessHandle.Info {
        override fun command(): Optional<String> = Optional.empty()

        override fun commandLine(): Optional<String> = Optional.empty()

        override fun arguments(): Optional<Array<String>> = Optional.empty()

        override fun startInstant(): Optional<java.time.Instant> = Optional.empty()

        override fun totalCpuDuration(): Optional<java.time.Duration> = Optional.empty()

        override fun user(): Optional<String> = Optional.empty()
    }
}
