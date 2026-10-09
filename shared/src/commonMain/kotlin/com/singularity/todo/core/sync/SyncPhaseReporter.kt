package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.original
import com.singularity.todo.core.observability.CrashReportingPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the mutable state shared between [SyncEngine] and [PushPhase].
 *
 * [SyncEngine] creates this holder, exposes its [status], [lastPush], and [lastPull]
 * as public `StateFlow`s, and passes the holder to [PushPhase] so both use the same
 * `SyncPhaseReporter` instance.
 *
 * This avoids duplicating the `MutableStateFlow`s while keeping the public
 * `StateFlow` API on `SyncEngine` intact.
 */
internal class SyncEngineState(
    log: Logger,
    crashReporter: CrashReportingPort,
) {
    private val _status = MutableStateFlow<SyncEngineStatus>(SyncEngineStatus.Idle)
    val status: StateFlow<SyncEngineStatus> = _status.asStateFlow()

    private val _lastPush = MutableStateFlow<Result<PushSummary>?>(null)
    val lastPush: StateFlow<Result<PushSummary>?> = _lastPush.asStateFlow()

    private val _lastPull = MutableStateFlow<Result<PullSummary>?>(null)
    val lastPull: StateFlow<Result<PullSummary>?> = _lastPull.asStateFlow()

    val phases = SyncPhaseReporter(
        log = log,
        crashReporter = crashReporter,
        status = _status,
        lastPush = _lastPush,
        lastPull = _lastPull,
    )
}

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
     *
     * Returns [Unit] rather than [Result] because the caller already receives the error
     * as a [PhaseResult.Failed] return value. The reporting here (log + crashReporter)
     * is the internal seam; the external contract is [PhaseResult.Failed].
     */
    fun pushFailed(error: AppError, pending: Int) {
        lastPush.value = Result.failure(error)
        log.e(error.original()) { "Push failed [${error.code}, pending=$pending]" }
        crashReporter.report(error.original(), error.code)
        status.value = SyncEngineStatus.Failure(error)
    }

    /**
     * The pull counterpart of [pushFailed], with the same obligation to be terminal.
     *
     * Returns [Unit] rather than [Result] because the caller already receives the error
     * as a [PhaseResult.Failed] return value. The reporting here (log + crashReporter)
     * is the internal seam; the external contract is [PhaseResult.Failed].
     */
    fun pullFailed(error: AppError, sinceLsn: Long) {
        lastPull.value = Result.failure(error)
        log.e(error.original()) { "Pull failed [${error.code}, sinceLsn=$sinceLsn]" }
        crashReporter.report(error.original(), error.code)
        status.value = SyncEngineStatus.Failure(error)
    }

    /**
     * Reports a failure that stopped the cycle before either phase ran.
     *
     * [SyncOutcome.CouldNotStart] rather than a [SyncOutcome.Completed] with the same
     * error in both phases: this function cannot know which phase a throw came from,
     * and filling both slots asserted work that may never have started.
     */
    fun cycleFailed(error: AppError): SyncOutcome {
        log.e(error.original()) { "Sync cycle could not start [${error.code}]" }
        crashReporter.report(error.original(), error.code)
        status.value = SyncEngineStatus.Failure(error)
        return SyncOutcome.CouldNotStart(error)
    }

    // ─── Phase status helpers ─────────────────────────────────────────────────

    /**
     * Sets the engine status to [SyncEngineStatus.Pushing] or [SyncEngineStatus.Idle].
     *
     * Used by [PushPhase] to transition the status at phase entry and exit.
     * [pushFailed] handles the failure transition separately.
     */
    fun setPushStatus(isPushing: Boolean) {
        status.value = if (isPushing) SyncEngineStatus.Pushing else SyncEngineStatus.Idle
    }

    /**
     * Records a successful push result.
     *
     * Used by [PushPhase] at the end of a successful (or discarded) push.
     * [pushFailed] handles the failure path separately.
     */
    fun recordPushResult(result: Result<PushSummary>) {
        lastPush.value = result
    }

    /**
     * Sets the engine status directly.
     *
     * Used by pull to transition to [SyncEngineStatus.Pulling], [SyncEngineStatus.Idle],
     * or [SyncEngineStatus.Failure].
     */
    fun setStatus(status: SyncEngineStatus) {
        this.status.value = status
    }

    /**
     * Records a pull result.
     *
     * Used by pull at the end of a successful (or stalled) pull.
     * [pullFailed] handles the failure path separately.
     */
    fun recordPullResult(result: Result<PullSummary>) {
        lastPull.value = result
    }
}

/**
 * Result of a single sync phase (push or pull).
 *
 * Mirrors the `Result` API so that existing tests that assert on `.exceptionOrNull()` and
 * `.isSuccess` require minimal change. New call sites should prefer the sealed arms directly.
 *
 * @see PushPhase
 * @see PullPhase
 */
sealed interface PhaseResult<out T> {
    fun isSuccess(): Boolean
    fun isFailure(): Boolean
    fun exceptionOrNull(): Throwable?
    fun getOrNull(): T?
    fun getOrThrow(): T

    /** Wraps a successful phase with its summary value. */
    data class Ok<T>(val value: T) : PhaseResult<T> {
        override fun isSuccess() = true
        override fun isFailure() = false
        override fun exceptionOrNull(): Throwable? = null
        override fun getOrNull(): T? = value
        override fun getOrThrow(): T = value
    }

    /** The phase ended with a classified [error]. */
    data class Failed<T>(val error: Throwable) : PhaseResult<T> {
        override fun isSuccess() = false
        override fun isFailure() = true
        override fun exceptionOrNull(): Throwable? = error
        override fun getOrNull(): T? = null
        override fun getOrThrow(): T = throw error
    }

    /** The phase did not run (signed out or no active scope). */
    data object NotRun : PhaseResult<Nothing> {
        override fun isSuccess() = false
        override fun isFailure() = true
        override fun exceptionOrNull(): Throwable? = IllegalStateException("Phase did not run")
        override fun getOrNull(): Nothing? = null
        override fun getOrThrow(): Nothing = throw IllegalStateException("Phase did not run")
    }

    /**
     * The phase ran and the server confirmed the patch, but the local outbox row was
     * already removed (coalesced by a later local edit). The server answered `ok: true`
     * and this device no longer holds the patch — no outbox action is needed.
     *
     * Push-only. Exposed here so [PushPhase.push] can return it directly.
     */
    data object Superseded : PhaseResult<Nothing> {
        override fun isSuccess() = true
        override fun isFailure() = false
        override fun exceptionOrNull(): Throwable? = null
        override fun getOrNull(): Nothing? = null
        override fun getOrThrow(): Nothing = throw IllegalStateException("Phase was superseded")
    }
}

/**
 * Converts a [PhaseResult] to a [Result], for call sites (such as tests) that
 * still expect the `Result`-based API.
 *
 * Specialised to [PushSummary] because that is the only phase that ever calls this
 * internally. The [Superseded] arm returns a zero-count [PushSummary]; no other `T`
 * has a meaningful value to construct here.
 */
internal fun PhaseResult<PushSummary>.toResult(): Result<PushSummary> = when (this) {
    is PhaseResult.Ok -> Result.success(value)
    is PhaseResult.Failed -> Result.failure(error)
    is PhaseResult.NotRun -> Result.failure(IllegalStateException("Phase did not run"))
    is PhaseResult.Superseded -> Result.success(PushSummary(0, 0, 0, superseded = 0))
}
