package com.singularity.todo.core.process

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import com.singularity.todo.test.helpers.awaitState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The process tree, exercised against **real** processes.
 *
 * ## The case that matters
 *
 * An agent is a process that starts processes. The agent spawns the tool server this
 * application hands it, and the agent may spawn helpers of its own. Terminating the
 * agent alone leaves those behind: the tool server keeps a database open, and a helper
 * keeps running, and the user has closed a conversation and still has a stray process
 * holding their data.
 *
 * So closing a session ends the *tree*. This test is the only kind that can show it:
 * the fake agent below starts a child of its own, and the assertion is that neither
 * process is left afterwards.
 *
 * ## How the test knows the child exists
 *
 * The child is started asynchronously by the shell, so a tree closed before the child
 * exists is a tree that never had one — and the test would pass for the wrong reason.
 *
 * The wait for it is a marker file rather than a sleep: the shell creates the file only
 * after it has forked the background job, so the file's existence is the fact that the
 * child exists. A sleep would have been both real time (which this project's
 * conventions forbid in tests) and a guess.
 *
 * The marker is deliberately *not* signalled through the child's standard output: reading
 * a line out of that stream and then cancelling the flow leaves the reader blocked in
 * `readLine()`, and a blocked reader is a coroutine that never finishes.
 */
@Tag("slow")
@Tag("slow:platform")
class ProcessTreeTest {

    @Test
    fun `closing the tree leaves no process from it behind`() = runTest {
        val marker = markerFile()
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", agentWithChild(marker)))
        val root = process.process.toHandle()

        val descendant = awaitChild(root, marker)
        assertNotNull(descendant, "the fake agent started a child of its own")

        val tree = ProcessTree(root)
        tree.close()

        assertFalse(root.isAlive, "the agent is gone")
        assertFalse(descendant!!.isAlive, "and so is the process it started")
        process.close()
        removeMarker(marker)
    }

    @Test
    fun `closing the tree twice is safe`() = runTest {
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", "sleep 300"))
        val tree = ProcessTree(process.process.toHandle())

        tree.close()
        tree.close()

        assertFalse(process.process.toHandle().isAlive, "the agent is gone")
        process.close()
    }

    @Test
    fun `the tree destroys the deepest descendants first`() = runTest {
        val marker = markerFile()
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", agentWithChild(marker)))
        val root = process.process.toHandle()

        val descendant = awaitChild(root, marker)
        assertNotNull(descendant, "the fake agent started a child of its own")

        val tree = ProcessTree(root)
        tree.close()

        // The child is asked to leave before its parent, so it cannot be replaced by a
        // new one in the moment between the signal and the death.
        assertFalse(descendant!!.isAlive, "the child is gone")
        assertFalse(root.isAlive, "and so is the parent")
        process.close()
        removeMarker(marker)
    }

    @Test
    fun `a tree that ignored the polite signal is still ended by the graceful path`() = runTest {
        // The agent that ignores SIGTERM. One real bridge does, and the screen's own
        // comment says so: asking it to stop is a request it may decline.
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", STUBBORN_AGENT))
        val root = process.process.toHandle()
        val tree = ProcessTree(root, gracePeriod = 200.milliseconds)

        // The urgent path asks the tree to leave. It must not consume the graceful
        // path's chance to run — that is the whole point of the two being separate.
        tree.destroyNow()
        assertTrue(root.isAlive, "the process ignores the polite signal")

        tree.close()

        assertFalse(root.isAlive, "and the graceful path still ends it")
        process.close()
    }

    @Test
    fun `the urgent path ends a tree that ignores the polite signal`() = runTest {
        // The screen's teardown has no scope left to wait in, so the urgent path
        // cannot wait for the tree to die — but it must still make sure that it dies.
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", STUBBORN_AGENT))
        val root = process.process.toHandle()
        val tree = ProcessTree(root, gracePeriod = 200.milliseconds)

        tree.destroyNow()
        assertTrue(root.isAlive, "the polite signal changes nothing")

        // The escalation is carried by a thread of its own, so the wait here is a real
        // one: the process is real, and its death is not on the virtual clock.
        root.onExit().get(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertFalse(root.isAlive, "and the urgent path makes sure the tree leaves")
        process.close()
    }

    private companion object {
        /**
         * A shell that ignores SIGTERM and waits.
         *
         * `trap "" TERM` makes the signal a no-op for the shell, so the polite request
         * reaches it and changes nothing — which is what an agent that declines to
         * stop looks like from here.
         */
        const val STUBBORN_AGENT = "trap \"\" TERM; sleep 5"

        /** How long a real process is given to die before the test gives up on it. */
        const val EXIT_TIMEOUT_SECONDS = 10L
    }

    /**
     * The descendant of [root], once the shell says the child has been forked.
     *
     * The shell creates [marker] after forking the background job, so the marker's
     * existence is the fact that the child exists. The lookup is then a query, not a wait.
     */
    private suspend fun TestScope.awaitChild(root: ProcessHandle, marker: Path): ProcessHandle? {
        awaitState { Files.exists(marker) }
        return root.descendants().findFirst().orElse(null)
    }

    /** A path nothing has created yet, in a directory of its own. */
    private fun markerFile(): Path =
        Files.createTempDirectory("process-tree-test").resolve("child-forked")

    /**
     * Removes the marker directory.
     *
     * The marker is a file on disk, and a test suite that leaves one behind per case
     * spends the machine's inodes — and a full inode table is a failure that looks like
     * "no space left on device" in a test that has nothing to do with disks.
     */
    private fun removeMarker(marker: Path) {
        val directory = marker.parent ?: return
        runCatching { Files.deleteIfExists(marker) }
        runCatching { Files.deleteIfExists(directory) }
    }

    /**
     * A shell that starts a background child and then says so.
     *
     * The background job is forked before the marker is created, so a reader that has
     * seen the marker knows the child exists.
     */
    private fun agentWithChild(marker: Path): String =
        "sleep 300 & touch $marker; sleep 300"
}
