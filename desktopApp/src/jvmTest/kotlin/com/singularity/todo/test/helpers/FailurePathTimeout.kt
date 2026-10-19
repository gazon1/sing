package com.singularity.todo.test.helpers

/**
 * Runs [block] under a hard wall-clock limit.
 *
 * A failing test must *fail*, not hang. The diagnostics that run on the failure path
 * — a semantics dump, a screenshot — talk to the Compose scene and to AWT's event
 * queue, and both can block for good: an endless recomposition keeps the scene busy,
 * and a blocked event queue never releases a caller waiting on it. Compose bounds its
 * own idle wait, but it does not bound the draw, and it does not bound AWT.
 *
 * The block therefore runs under a watchdog that interrupts the calling thread once
 * the limit is reached. An interrupt unwinds the blocking call — AWT's
 * `EventQueue.invokeAndWait` waits interruptibly — and the block unwinds with it.
 * The thread's interrupt flag is cleared afterwards so the failure can still be
 * reported: a test that fails with an interrupt flag set can lose its own report on
 * the way out.
 *
 * The limit is a *diagnostics* budget, not a test budget: it only ever applies to work
 * that runs after the test has already failed, and its only job is to make sure the
 * failure reaches the report.
 *
 * @param timeoutMs how long [block] may run before the watchdog interrupts it.
 * @param fallback the value to return when the block was interrupted.
 * @param block the work to run.
 * @return the block's result, or [fallback] when the block was interrupted.
 */
internal fun <T> withFailurePathTimeout(timeoutMs: Long, fallback: T, block: () -> T): T {
    val thread = Thread.currentThread()
    val watchdog = Thread({
        try {
            Thread.sleep(timeoutMs)
        } catch (e: InterruptedException) {
            // The block finished first — nothing to interrupt.
            return@Thread
        }
        thread.interrupt()
    }, "failure-path-watchdog").apply { isDaemon = true }
    watchdog.start()
    return try {
        block()
    } catch (e: InterruptedException) {
        fallback
    } finally {
        watchdog.interrupt()
        // Clear a flag the watchdog may have set a moment before the block returned.
        // Leaving it set would make the *reporting* of the failure fail too.
        Thread.interrupted()
    }
}
