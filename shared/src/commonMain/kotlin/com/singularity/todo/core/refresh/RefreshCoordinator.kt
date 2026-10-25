package com.singularity.todo.core.refresh

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.core.sync.SyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single owner of a refresh.
 *
 * Every screen that offers the gesture comes here, and only one refresh runs
 * at a time. Eighteen screens each starting their own would archive the
 * completed work eighteen times and put eighteen cycles on one outgoing queue.
 *
 * ## Why one run and two steps
 *
 * A refresh is the archive step and the synchronisation step, in that order,
 * and they are one run because the order matters. Archiving writes the
 * archived tasks to the outgoing queue; a synchronisation that pushed before
 * the archive step ran would leave the server holding the un-archived copy,
 * and the next download would put every task the user had just cleared away
 * back into the list.
 *
 * The two steps fail independently. A refresh that could not reach the server
 * still archives, because the archive is a local operation and the user asked
 * for it; a refresh whose archive step failed still synchronises, because the
 * synchronisation is what carries everything else the user did.
 *
 * ## Why the run lives here and not in the caller
 *
 * The run executes in the owner's scope, not in the scope of whoever asked.
 * A refresh the user walked away from has to finish — otherwise the archive
 * step is half done and the synchronisation never ran, with nothing to show
 * that it stopped.
 *
 * ## A second request joins the run that is running
 *
 * A request that arrives while a refresh is running does **not** start a
 * second one. It joins the running one and receives that run's outcome. This
 * is stricter than the synchronisation coordinator's coalescing, which lets a
 * request that arrives mid-cycle queue one follow-up cycle: there, the
 * follow-up carries the periodic sync's own work, and skipping it would drop
 * a scheduled cycle. Here the request is the same gesture the user just made
 * — a second archive pass would move nothing new and a second push would put
 * the same rows on the wire twice.
 *
 * The consequence is that the number of archive calls and the number of sync
 * calls in a burst is one, no matter how many screens asked.
 */
class RefreshCoordinator(
    private val archiver: CompletedTaskArchiver,
    private val syncRepository: SyncRepository,
    private val scope: AutoCloseableCoroutineScope,
    private val log: Logger = Logger.withTag("RefreshCoordinator"),
) : AutoCloseable {
    /**
     * Guards the decision "start a run" against "join the run in progress".
     *
     * A lock rather than a channel: the channel would carry a *signal* to
     * start a run, and a signal that arrives mid-run is exactly the second
     * run the requirement forbids. The lock makes the decision and the
     * hand-out of the run's result one atomic step.
     */
    private val mutex = Mutex()

    /**
     * The run in progress, if any. Volatile so [close] can reach it without
     * suspending — a shutdown path must not need a coroutine to complete.
     */
    @Volatile
    private var currentRun: CompletableDeferred<RefreshOutcome>? = null

    /** Set by [close]; after it, a request stops immediately. */
    @Volatile
    private var closed = false

    /**
     * Requests a refresh and suspends until a refresh has completed.
     *
     * When a refresh is already running, this joins it and returns *that*
     * refresh's outcome — the same outcome the first requester gets.
     *
     * @return the outcome of the refresh this request joined or started.
     */
    suspend fun request(): RefreshOutcome {
        if (closed) return nothingHappened()
        val run = mutex.withLock {
            if (closed) return nothingHappened()
            // A run is in progress: join it rather than starting a second one.
            currentRun?.let { return@withLock it }
            CompletableDeferred<RefreshOutcome>().also { fresh ->
                currentRun = fresh
                // The run is started here, inside the lock, so that no second
                // request can slip between "there is no run" and "there is".
                // It runs in this coordinator's scope, not the caller's.
                //
                // ATOMIC: the coroutine starts even if the scope has already
                // been cancelled, so the body always reaches the `finally` in
                // runAndComplete and the waiters are released. Without it, a
                // scope that died first would leave every waiter suspended
                // forever.
                scope.launch(start = CoroutineStart.ATOMIC) { runAndComplete(fresh) }
            }
        }
        return run.await()
    }

    /**
     * Closes the coordinator. Requests after it stop immediately, and a waiter
     * on a run in progress is released rather than left suspended forever.
     */
    override fun close() {
        closed = true
        currentRun?.complete(nothingHappened())
    }

    /**
     * Runs one refresh and hands its outcome to everyone waiting for it.
     *
     * The `finally` is what keeps a waiter from hanging: whether the run
     * finished, failed or was cancelled, the waiters are released.
     */
    private suspend fun runAndComplete(run: CompletableDeferred<RefreshOutcome>) {
        try {
            val outcome = runRefreshCatching()
            mutex.withLock {
                // Cleared before the result is published: a request that arrives
                // after this point is a request that came after the refresh
                // finished, and it must start a new one.
                if (currentRun === run) currentRun = null
            }
            run.complete(outcome)
        } finally {
            run.complete(nothingHappened())
        }
    }

    /**
     * Runs one refresh, converting an escaping exception into a failed outcome.
     *
     * A throw here would leave every waiter suspended forever — the run
     * would never complete, and the screen's indicator would never come back
     * down.
     */
    @Suppress("TooGenericExceptionCaught") // a step may throw anything; the run must still report
    private suspend fun runRefreshCatching(): RefreshOutcome = try {
        runRefresh()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Refresh threw; reporting the failure" }
        RefreshOutcome(archived = Result.failure(e), sync = SyncOutcome.CouldNotStart(e.toAppError()))
    }

    /**
     * The archive step, then the synchronisation step.
     *
     * The order is the point — see the class doc.
     */
    private suspend fun runRefresh(): RefreshOutcome {
        val archived = archiveStep()
        val sync = syncStep()
        return RefreshOutcome(archived = archived, sync = sync)
    }

    /**
     * The archive step, isolated: a failure here must not stop the
     * synchronisation that follows it.
     */
    @Suppress("TooGenericExceptionCaught") // a repository may throw anything
    private suspend fun archiveStep(): Result<Int> = try {
        archiver.archiveCompletedTasks()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * The synchronisation step, isolated: a failure here must not undo the
     * archive that came before it.
     */
    @Suppress("TooGenericExceptionCaught") // a repository may throw anything
    private suspend fun syncStep(): SyncOutcome = try {
        syncRepository.syncOnce()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SyncOutcome.CouldNotStart(e.toAppError())
    }

    /**
     * The outcome of a refresh that did not happen. Nothing moved and there
     * was no synchronisation to run.
     */
    private fun nothingHappened(): RefreshOutcome =
        RefreshOutcome(archived = Result.success(0), sync = SyncOutcome.NothingToDo)
}
