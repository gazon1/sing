package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.original
import com.singularity.todo.core.observability.CrashReportingPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Every way a sync phase can end badly, in one place.
 *
 * ## Why this is not four methods on the engine
 *
 * It was. The obligation they carry — *a phase must never leave the status advertising
 * work in progress* — is easy to honour in one of four call sites and easy to forget in
 * the other three, and the way it was forgotten was expensive: a status stranded at
 * `Pushing` reads as "a cycle is running", and `isRunning()` is the gate the sync screen
 * checks before starting another one, so a single storage failure made every later
 * attempt refuse itself. Making the reporters the only way to end a phase badly means a
 * new call site has to choose one, and there is no fourth thing to forget.
 *
 * They also lived here because the class they belonged to had run out of room for
 * concerns that are not the engine's: [SyncEngine] decides *what* to send, and this
 * decides how a phase ended.
 */
internal class SyncPhaseReporter(
    private val log: Logger,
    private val crashReporter: CrashReportingPort,
    private val status: MutableStateFlow<SyncEngineStatus>,
    private val lastPush: MutableStateFlow<Result<PushSummary>?>,
    private val lastPull: MutableStateFlow<Result<PullSummary>?>,
) {

    /**
     * Runs [block] — a local database read or write — reporting a failure as
     * [AppError.Persistence].
     *
     * The classification happens here, at the call site, because that is the only place
     * that knows. A `SQLiteException`, an `IllegalStateException` from a closed Room
     * database, and a `RuntimeException` from a DAO that does not exist all arrive as
     * an unlabelled `Throwable`; deciding from the class is what made storage
     * indistinguishable from a server that refused, and it is also why the call sites
     * each had to catch separately and none of them recorded what it was reading.
     *
     * The message names the operation rather than repeating the driver's, because the
     * driver's is the half the user cannot act on. `SQLiteException` says "attempt to
     * re-open an already-closed object"; "Could not read the pending changes" says what
     * to do next. The driver's text is kept as the [AppError.cause], so nothing is lost
     * from the crash report.
     *
     * @param code a stable, machine-shaped identifier a crash reporter groups on. It is
     *   a separate argument rather than being derived from [what] because [what] is
     *   prose that may be reworded, and a code that changes when a message does cannot
     *   group anything. Two Storage failures from the same call site are one dashboard
     *   group; two different call sites are two.
     */
    @Suppress("TooGenericExceptionCaught") // a DAO may throw anything, and nothing may escape a phase
    suspend fun <T> localStorage(
        code: String,
        what: String,
        block: suspend () -> T,
    ): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(AppError.Persistence("Could not $what", code = code, cause = e))
    }

    /**
     * Records a failed push and lands the status on a terminal value.
     *
     * @param pending how many patches were waiting; it is the context a crash report
     *   needs to tell "one patch could not be built" from "the outbox was full".
     */
    fun pushFailed(error: AppError, pending: Int): Result<PushSummary> {
        lastPush.value = Result.failure(error)
        log.e(error.original()) { "Push failed [${error.code}, pending=$pending]" }
        crashReporter.report(error.original(), error.code)
        status.value = SyncEngineStatus.Failure(error)
        return Result.failure(error)
    }

    /** The pull counterpart of [pushFailed], with the same obligation to be terminal. */
    fun pullFailed(error: AppError, sinceLsn: Long): Result<PullSummary> {
        lastPull.value = Result.failure(error)
        log.e(error.original()) { "Pull failed [${error.code}, sinceLsn=$sinceLsn]" }
        crashReporter.report(error.original(), error.code)
        status.value = SyncEngineStatus.Failure(error)
        return Result.failure(error)
    }

    /**
     * Reports a failure that stopped the cycle before either phase ran.
     *
     * [SyncOutcome.Failed] rather than a [SyncOutcome.Success] with the same error in
     * both phases: this function cannot know which phase a throw came from, and filling
     * both slots asserted work that may never have started.
     */
    fun cycleFailed(error: AppError): SyncOutcome {
        log.e(error.original()) { "Sync cycle could not start [${error.code}]" }
        crashReporter.report(error.original(), error.code)
        status.value = SyncEngineStatus.Failure(error)
        return SyncOutcome.Failed(error)
    }
}
