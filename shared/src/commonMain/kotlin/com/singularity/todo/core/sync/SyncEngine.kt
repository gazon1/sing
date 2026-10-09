package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.accountIdOrNull
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.original
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonObject

/**
 * Summary of a push operation.
 *
 * [discarded] counts results the server answered that this client refused to apply,
 * because the account the request was made under is no longer signed in (REQ-UA-018).
 * It is a fourth number rather than a [failed] one because nothing failed: the server
 * accepted the work, and the local device has declined to act on the answer. Counting
 * it as [succeeded] would mark rows delivered that are still queued; counting it as
 * [failed] would say the server rejected patches it accepted, which is the wrong
 * repair to go looking for. It carries the weight of a distinct field precisely so
 * that neither of those two readings is available by accident — the old three-field
 * summary could not say "the server took it and we ignored it" at all.
 *
 * [lost] counts patches whose every field lost a per-field race on the server. The
 * server reports these as `ok: true`, so [succeeded] would claim the user has the
 * edit somewhere the server agrees with, and [failed] would send an operator looking
 * for a rejection that never happened. It is a fourth number so that neither reading
 * is available by accident — and so that a summary can answer "did this device lose
 * anything today", which is a question about a user's work rather than about a
 * transport. See REQ-OS-026.
 *
 * [superseded] counts patches the server confirmed (`ok: true`) but whose local outbox
 * row was already removed by the time the response arrived (a later local edit
 * coalesced it away). No outbox or shadow action is needed for these.
 */
data class PushSummary(
    val processed: Int,
    val succeeded: Int,
    val failed: Int,
    val discarded: Int = 0,
    val lost: Int = 0,
    val superseded: Int = 0,
)

/**
 * Summary of a pull operation.
 *
 * [dropped] counts events this client could not apply. It is the difference between
 * "the server sent three events and two are stored" and "three events were consumed
 * and one was thrown away" — a distinction the old three-field summary could not
 * express, which is why a permanently unappliable event was invisible.
 *
 * ## Why there is no conflict count
 *
 * There used to be one, and it was always zero in production. `ApplyOutcome.Conflict`
 * was produced only by a catch block that classified every throw out of a repository
 * write as "applied, but a field lost to a newer one" — which is what made a full disk
 * move the cursor past an event whose row was never written (fixed in `ebb3c1dc`). With
 * that gone, nothing constructs it.
 *
 * The reason it could not be real is structural: applying a pull event is a wholesale
 * upsert of the remote document, with no field-level merge, so "a field lost to a newer
 * one" cannot occur on this path. The per-field HLC merge that could produce it lives on
 * the push side. A counter that cannot become non-zero is the same kind of lie the pull
 * summary used to tell.
 */
data class PullSummary(val received: Int, val applied: Int, val dropped: Int = 0)

/**
 * How many events one pull request asks for.
 *
 * A page is the unit the feed is read in, and the loop reads until a short page says
 * it has reached the end. The value is a trade, not a constant of nature: a larger page
 * means fewer round trips and a longer pause holding the status as [Pulling], a smaller
 * one means the opposite. 100 is an order of magnitude above the median backlog and far
 * below the point where a page takes noticeable time to apply, so most cycles finish in
 * one request and the ones that do not are the ones that genuinely had more to read.
 */
const val PULL_PAGE_SIZE = 100

/**
 * Outcome of a single sync run (push + pull).
 */
sealed interface SyncOutcome {
    /**
     * A sync completed (push and/or pull ran to completion — even if individual
     * operations had errors, the engine finished its cycle).
     */
    data class Completed(val push: Result<PushSummary>, val pull: Result<PullSummary>) : SyncOutcome

    /**
     * The cycle never started, because a local read both phases depend on failed.
     *
     * This case exists because the alternative is a lie. [Completed] has two slots, one
     * per phase, and a failure before either phase ran has nowhere honest to go: put
     * the same error in both and the caller learns that a push *and* a pull failed,
     * which is twice the work and none of it happened. The coordinator's own
     * "something threw" fallback had exactly this shape, and reported a failed pull
     * for a pull that was never entered.
     *
     * @property error [AppError.Persistence] in practice — a scope read or a cursor
     *   read — but the contract is "a local dependency failed", not a specific type.
     */
    data class CouldNotStart(val error: AppError) : SyncOutcome

    /**
     * No cycle ran because there was no active sync scope (signed out, or no profile).
     *
     * It is deliberately **not** "another sync was already running". A request that
     * arrives mid-cycle is absorbed by the conflated channel and answered with the
     * outcome of the cycle that served it — the caller gets its work done, not a
     * rejection it now has to reason about. This case means there is no work to do,
     * which is a genuinely different thing and needs a different reaction.
     */
    data object NothingToDo : SyncOutcome
}

/**
 * Status of the sync engine.
 *
 * [NoConnection] is an operational state — the device is offline, not failed.
 * All other errors are wrapped in [Failure].
 */
sealed interface SyncEngineStatus {
    data object Idle : SyncEngineStatus
    data object Pushing : SyncEngineStatus
    data object Pulling : SyncEngineStatus
    data object NoConnection : SyncEngineStatus
    data class Failure(val error: AppError) : SyncEngineStatus

    fun isRunning(): Boolean = this is Pushing || this is Pulling

    fun isSuccess(): Boolean = this is Idle || this is NoConnection
}

/**
 * Outcome of applying a [SyncEvent] during pull.
 *
 * The distinction that matters is not applied versus not-applied but **whether trying
 * again could ever help**, because that is what decides the cursor. Every arm here
 * except [Applied] used to be reported as [Applied], and the result was a pull summary
 * that said it had received an event and applied it while the row was never written.
 *
 * ## Why there is no Conflict arm
 *
 * There was one — "applied, but a field lost to a newer one" — and it was only ever
 * produced by a catch block that classified *every* throw out of a repository write
 * that way. That is what turned a full disk into a cursor that moved past an event
 * whose row was never written (`ebb3c1dc`).
 *
 * It could not have been real even when it was: applying a pull event is a wholesale
 * upsert of the remote document with no field-level merge, so nothing can be lost to a
 * newer field on this path. The per-field HLC merge that makes that possible lives on
 * the push side. If a merge is ever added here, the arm comes back with it — and
 * `ApplyOutcomeArmsTest` fails until someone explains why.
 */
sealed interface ApplyOutcome {
    data object Applied : ApplyOutcome

    /**
     * This event cannot be applied and never will be — a payload that is absent or is
     * not a document. Retrying re-delivers the same bytes, so the cursor advances past
     * it and the event is counted as dropped.
     *
     * Advancing is the whole point. A permanently unusable event that blocked the cursor
     * would stop the account syncing anything else, forever, over one bad row.
     */
    data class Skipped(val reason: String) : ApplyOutcome

    /**
     * This event could not be applied but a later attempt might succeed — a delete
     * that failed, an upsert that hit a storage error. The cursor stays here, so the
     * event is delivered again on the next cycle.
     */
    data class Failed(val reason: String) : ApplyOutcome
}

/**
 * Fun interface for applying a pull event to a local entity.
 */
fun interface EntityApply {
    suspend fun apply(event: SyncEvent): ApplyOutcome
}

/**
 * Sync engine — orchestrates push and pull operations.
 *
 * Polling is the caller's responsibility (see [SyncRunner]). This class only
 * provides [enqueue], [syncOnce], and [registerHandler].
 *
 * ## Phase extraction
 *
 * Push logic lives in [PushPhase]; pull logic lives in [PullPhase]. The engine
 * delegates to both and coordinates the cycle in [syncOnce].
 */
internal class SyncEngine(
    private val log: Logger,
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val outboxDao: SyncOutboxDao,
    private val deadLetterDao: SyncDeadLetterDao,
    private val idGenerator: IdGenerator,
    private val stateRepository: SyncStateRepository,
    private val scopeProvider: SyncScopeProvider,
    /**
     * Bootstraps pull handlers for all DocTypes.
     *
     * Nullable here (a no-op placeholder) because Koin only instantiates a bean
     * when it is first resolved. The real `SyncBootstrapper` is created in a
     * separate `single {}` block in `CoreDiModule` — after the engine is cached —
     * so that its `init {}` (which registers the handlers) runs with a valid engine.
     */
    @Suppress("UNUSED_PARAMETER")  // side-effect only: init {} in real Bootstrapper
    private val bootstrapper: SyncBootstrapper? = null,
    private val shadowDao: SyncShadowDao,
    private val patchBuilder: SyncPatchBuilder,
    /**
     * Supplies the per-type document writer, on demand rather than eagerly.
     *
     * A provider and not the writer itself because of a real dependency cycle, not for
     * laziness's sake:
     *
     * ```
     * TaskRepository → SyncRepository → SyncEngine → SyncDocumentWriter → TaskRepository
     * ```
     *
     * `SyncDocumentWriter` needs the repositories (that is what it is a table of), and
     * the repositories need the engine (that is what `enqueue` is). Resolving the
     * writer as a constructor argument closes the loop, and Koin recurses until the
     * stack gives out — which it did, in a graph that resolves the chain, rather than
     * at module-definition time where it would have been obvious.
     *
     * A provider is the honest way out: the writer is only needed to resolve a lost
     * race, by which point the repositories exist. The alternative — reaching into the
     * repositories from the engine directly, which is the direction the layering runs
     * away from — trades a runtime failure for a worse structural one.
     */
    private val writerProvider: () -> SyncDocumentWriter,
    private val scheduler: SyncWorkScheduler,
    private val retryPolicy: PatchRetryPolicy = PatchRetryPolicy(),
    /**
     * Wall-clock time, injected.
     *
     * The backoff, the patch timestamp, the outbox row's creation time and the
     * "last synced" stamp are all wall-clock readings, and all of them used to be
     * taken from `System.currentTimeMillis()` at six call sites. That made every
     * time-dependent behaviour of this class untestable: a test could not observe a
     * deferral, could not let one expire, and could not assert what timestamp a patch
     * carries. Required rather than defaulted, so a new call site has to decide
     * rather than inherit the wall clock.
     */
    private val clock: Clock,
    private val scope: AutoCloseableCoroutineScope,
    private val crashReporter: CrashReportingPort,
    /**
     * Per-entity pull handlers (registered by TasksDiModule, NotesDiModule, etc.).
     *
     * Default constructs a real instance so that test helpers that construct [SyncEngine]
     * without this parameter continue to work.
     */
    private val handlerRegistry: HandlerRegistry = HandlerRegistry(),
    /**
     * Shared mutable state and phase reporter. [SyncEngine] exposes its [SyncEngineState.status],
     * [SyncEngineState.lastPush], and [SyncEngineState.lastPull] as public `StateFlow`s.
     * [PushPhase] uses the same [SyncEngineState.phases] reporter.
     *
     * Default constructs a real instance so that test helpers that construct [SyncEngine]
     * without this parameter continue to work.
     */
    internal val state: SyncEngineState = SyncEngineState(log, crashReporter),
    /**
     * The push phase. Delegates status management and result recording to [state].
     *
     * Default constructs a real instance so that test helpers that construct [SyncEngine]
     * without this parameter continue to work.
     */
    private val pushPhase: PushPhase = PushPhase(
        api = api,
        authRepository = authRepository,
        outboxDao = outboxDao,
        deadLetterDao = deadLetterDao,
        shadowDao = shadowDao,
        idGenerator = idGenerator,
        scopeProvider = scopeProvider,
        patchBuilder = patchBuilder,
        clock = clock,
        phases = state.phases,
        writerProvider = writerProvider,
        retryPolicy = retryPolicy,
        scope = scope,
    ),
    /**
     * The pull phase. Uses [state] for status and result reporting.
     *
     * Default constructs a real instance so that test helpers that construct [SyncEngine]
     * without this parameter continue to work.
     */
    private val pullPhase: PullPhase = PullPhase(
        api = api,
        authRepository = authRepository,
        stateRepository = stateRepository,
        clock = clock,
        scopeProvider = scopeProvider,
        phases = state.phases,
        getHandlers = { handlerRegistry.handlers },
        scope = scope,
    ),
) : AutoCloseable by scope {
    private val json = StableJson

    /** Exposes the shared [SyncEngineState.status]. */
    val status: StateFlow<SyncEngineStatus> = state.status

    /** Exposes the shared [SyncEngineState.lastPush]. */
    val lastPush: StateFlow<Result<PushSummary>?> = state.lastPush

    /** Exposes the shared [SyncEngineState.lastPull]. */
    val lastPull: StateFlow<Result<PullSummary>?> = state.lastPull

    val handlers: Map<DocType, EntityApply> get() = handlerRegistry.handlers

    init {
        // React to session changes: enqueue push when signed in, cancel when signed out.
        // WorkManager handles back-off and persistence across process death.
        scope.launch {
            authRepository.currentSession.collect { session ->
                when (session) {
                    is Session.SignedIn -> scheduler.enqueuePush()

                    is Session.Anonymous,
                    is Session.SignedOut,
                    is Session.Loading,
                    -> scheduler.cancelPush()
                }
            }
        }
    }

    /**
     * Registers a handler for pull events of the given [DocType].
     */
    fun registerHandler(docType: DocType, apply: EntityApply) {
        handlerRegistry.registerHandler(docType, apply)
    }

    /**
     * Enqueues an entity change for sync.
     *
     * ## Why this reports its own failure
     *
     * The local row is committed inside `unitOfWork.write`, **before** this is called. A
     * failure here is therefore not a failed write — it is a write that succeeded locally
     * and will never reach the server, because the patch row was never inserted and no
     * later cycle has anything to notice. Every other layer reports the same event: the
     * edit is in the database, and the sync screen says "up to date".
     *
     * Eighteen repository call sites discard the returned `Result`, so reporting belongs
     * here rather than in each of them: this is the one place that knows a patch failed
     * to be queued, and eighteen log statements across six data-layer classes would still
     * be silent at the next call site someone writes.
     *
     * The `Result` stays. Reporting is not a replacement for the return value — a caller
     * that wants to react still can, and the contract a test asserts on is unchanged.
     *
     * No cancellation branch: `runCatchingResult` rethrows `CancellationException` before
     * it can become a `Result`, so anything visible here is a real error. Guarding
     * against cancellation would be a guard against something the type already excludes,
     * and a cancelled enqueue is not an outage.
     *
     * The silence this replaces was not harmless. `2026-09-27-write-layer-soundness.md`
     * MR-4 found `Note` lacked `@Serializable`, so `toJson()` threw for every note
     * enqueue: notes stopped syncing for every user, with no error anywhere. MR-5 found
     * the same in `Project` and `Tag`.
     *
     * @see REQ-OS-028
     */
    suspend fun enqueue(entity: SyncableEntity): Result<Unit> = runCatchingResult {
        // D4 fix: wrap all storage access with localStorage so failures are classified
        // as AppError.Persistence. getOrElse { throw it } propagates the classified error
        // so runCatchingResult's catch block and the .also reporter both execute normally.
        val active = state.phases
            .localStorage("sync.scope.read", "read the active sync scope for enqueue") {
                scopeProvider.current.first()
            }
            .getOrElse { throw it }
            ?: return@runCatchingResult

        val patch = patchBuilder.build(entity, active, clock.now().toEpochMilliseconds())
        val payload = json.encodeToString(patch)

        // Coalesce per entity before inserting, so an entity has at most one patch
        // in flight. The new patch is built against the previous in-flight state, so
        // it carries every field the one it replaces carried — replacing loses
        // nothing. Without this the outbox grows without bound under fast editing,
        // and the shadow's "promote only if this patch still owns the marker" guard
        // could never fire.
        // The owner is the scope this patch was built under — not a scope read again
        // here, which could be a different one if the profile moved between the two.
        // The patch itself was diffed against that scope's shadow, so it belongs to it.
        state.phases
            .localStorage("sync.outbox.delete", "coalesce the outbox row before insert") {
                outboxDao.deleteByEntity(active.ownerId, entity.syncId)
            }
            .getOrElse { throw it }

        state.phases
            .localStorage("sync.outbox.insert", "insert the pending patch") {
                outboxDao.insert(
                    SyncOutboxEntity(
                        patchId = patch.patchId,
                        ownerId = active.ownerId,
                        entityId = entity.syncId,
                        entityType = entity.docType.key,
                        payload = payload,
                        createdAt = clock.now().toEpochMilliseconds(),
                    ),
                )
            }
            .getOrElse { throw it }
    }.also { result ->
        val error = result.exceptionOrNull() ?: return@also
        val appError = error as? AppError ?: error.toAppError()
        // `original()`, not the wrapper: runCatchingResult builds every AppError in a
        // catch block, so reporting one hands over a stack that ends where it was
        // constructed. This is the same pairing SyncPhaseReporter uses for every other
        // sync failure — see AppError.original().
        log.e(appError.original()) {
            "enqueue failed for ${entity.docType.key} ${entity.syncId}: " +
                "the change is saved locally and will never be pushed"
        }
        crashReporter.report(appError.original(), "sync.enqueue_failed")
    }

    /**
     * Runs one push + pull cycle.
     */
    internal suspend fun syncOnce(): SyncOutcome {
        val active = state.phases.localStorage("sync.scope.read", "read the active sync scope") {
            scopeProvider.current.first()
        }
            .getOrElse { return state.phases.cycleFailed(it.toAppError()) }
            ?: return SyncOutcome.NothingToDo

        val cursor = state.phases.localStorage("sync.cursor.read", "read the download cursor") {
            stateRepository.get(active).lastLsn
        }
            .getOrElse { return state.phases.cycleFailed(it.toAppError()) }

        val pushResult = pushPhase.push()
        val pullResult = pullPhase.pull(active, sinceLsn = cursor)
        val push = when (pushResult) {
            is PhaseResult.Ok -> Result.success(pushResult.getOrThrow())
            // NotRun is unreachable: push() returns Ok/Failed or throws; it never returns NotRun.
            // Suppressed instead of removed so a future caller that adds NotRun gets a
            // compile warning rather than a silent missing-arm crash.
            is PhaseResult.NotRun -> @Suppress("UNREACHABLE_CODE") Result.failure(IllegalStateException("Phase did not run"))
            is PhaseResult.Failed -> Result.failure(pushResult.error)
            is PhaseResult.Superseded -> Result.success(PushSummary(0, 0, 0, superseded = 0))
        }
        val pull = when (pullResult) {
            is PhaseResult.Ok -> Result.success(pullResult.getOrThrow())
            // NotRun is unreachable: pull() returns NotRun only when !SignedIn, but
            // `active ?: return NothingToDo` guards this call before it is reached.
            is PhaseResult.NotRun -> @Suppress("UNREACHABLE_CODE") Result.success(PullSummary(0, 0, 0))
            is PhaseResult.Failed -> Result.failure(pullResult.error)
            is PhaseResult.Superseded -> Result.success(PullSummary(0, 0, 0))
        }
        return SyncOutcome.Completed(push = push, pull = pull)
    }

    /**
     * Convenience for tests: runs the push phase and returns a [Result].
     *
     * Production callers should use [syncOnce] which coordinates push and pull.
     */
    internal suspend fun push(): Result<PushSummary> = pushPhase.push().toResult()
}
