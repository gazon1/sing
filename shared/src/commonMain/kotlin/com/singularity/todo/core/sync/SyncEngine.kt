package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.accountIdOrNull
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
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
 */
data class PushSummary(
    val processed: Int,
    val succeeded: Int,
    val failed: Int,
    val discarded: Int = 0,
    val lost: Int = 0,
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
    data class Success(val push: Result<PushSummary>, val pull: Result<PullSummary>) : SyncOutcome

    /**
     * The cycle never started, because a local read both phases depend on failed.
     *
     * This case exists because the alternative is a lie. [Success] has two slots, one
     * per phase, and a failure before either phase ran has nowhere honest to go: put
     * the same error in both and the caller learns that a push *and* a pull failed,
     * which is twice the work and none of it happened. The coordinator's own
     * "something threw" fallback had exactly this shape, and reported a failed pull
     * for a pull that was never entered.
     *
     * @property error [AppError.Persistence] in practice — a scope read or a cursor
     *   read — but the contract is "a local dependency failed", not a specific type.
     */
    data class Failed(val error: AppError) : SyncOutcome

    /**
     * No cycle ran, and none will: the request could not be handed to the
     * [SyncCoordinator], which happens when the coordinator is closed.
     *
     * It is deliberately **not** "another sync was already running". A request that
     * arrives mid-cycle is absorbed by the conflated channel and answered with the
     * outcome of the cycle that served it — the caller gets its work done, not a
     * rejection it now has to reason about. This case means there is no work left to
     * do, which is a genuinely different thing and needs a different reaction.
     */
    data class Skipped(val reason: String) : SyncOutcome
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
     * that failed, an upsert that hit a storage error. The cursor stays put, so the
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
) : AutoCloseable by scope {
    private val json = StableJson

    /** One reading of the injected clock, in the unit this class stores. */
    private fun now(): Long = clock.now().toEpochMilliseconds()

    private val _status = MutableStateFlow<SyncEngineStatus>(SyncEngineStatus.Idle)
    val status: StateFlow<SyncEngineStatus> = _status.asStateFlow()

    private val _lastPush = MutableStateFlow<Result<PushSummary>?>(null)
    val lastPush: StateFlow<Result<PushSummary>?> = _lastPush.asStateFlow()

    private val _lastPull = MutableStateFlow<Result<PullSummary>?>(null)
    val lastPull: StateFlow<Result<PullSummary>?> = _lastPull.asStateFlow()

    /**
     * How a phase ends. Held rather than inherited so that "a phase must not leave the
     * status advertising work in progress" has exactly one implementation — see
     * [SyncPhaseReporter].
     */
    private val phases = SyncPhaseReporter(log, crashReporter, _status, _lastPush, _lastPull)

    // Per-entity pull handlers (registered by TasksDiModule, NotesDiModule, etc.)
    private val _handlers = MutableStateFlow<Map<DocType, EntityApply>>(emptyMap())
    val handlers: Map<DocType, EntityApply> get() = _handlers.value

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
        _handlers.value += (docType to apply)
    }

    /**
     * Enqueues an entity change for sync.
     */
    suspend fun enqueue(entity: SyncableEntity): Result<Unit> = runCatchingResult {
        val active = scopeProvider.current.first()
            ?: return@runCatchingResult
        val patch = patchBuilder.build(entity, active, now())
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
        outboxDao.deleteByEntity(active.ownerId, entity.syncId)
        outboxDao.insert(
            SyncOutboxEntity(
                patchId = patch.patchId,
                ownerId = active.ownerId,
                entityId = entity.syncId,
                entityType = entity.docType.key,
                payload = payload,
                createdAt = now(),
            ),
        )
    }

    /**
     * Runs one push + pull cycle.
     */
    internal suspend fun syncOnce(): SyncOutcome {
        val active = phases.localStorage("sync.scope.read", "read the active sync scope") {
            scopeProvider.current.first()
        }
            .getOrElse { return phases.cycleFailed(it.toAppError()) }
            ?: return SyncOutcome.Skipped("No active sync scope (signed out, or no profile)")

        val cursor = phases.localStorage("sync.cursor.read", "read the download cursor") {
            stateRepository.get(active).lastLsn
        }
            .getOrElse { return phases.cycleFailed(it.toAppError()) }

        val push = push()
        val pull = pull(active, sinceLsn = cursor)
        return SyncOutcome.Success(push, pull)
    }

    /**
     * What one push is about to send: the outbox rows it covers, and the request.
     *
     * Both travel together because the response is answered per patch id, and looking a
     * patch up in the outbox after the fact is how a row that was coalesced away between
     * the read and the answer gets mistaken for one that is still queued.
     */
    private data class PushPlan(
        val pending: List<SyncOutboxEntity>,
        val patches: List<DeltaPatch>,
        val request: BatchPushRequest,
        /**
         * The scope this cycle belongs to, captured with the request.
         *
         * It settles the shadow rows when the server answers, and re-reading the scope
         * then would let a profile switch in between put this cycle's patches under one
         * profile and their shadow under another.
         */
        val active: SyncScope?,
    )

    /**
     * Assembles what to send, or reports why it could not be assembled.
     *
     * A `null` plan is the ordinary "nothing to push" case, kept distinct from a failure
     * so that [push] can tell a quiet cycle from a broken one without a second read.
     */
    private suspend fun planPush(): Result<PushPlan?> {
        val nowMillis = now()

        // Read the scope FIRST, because it is what says whose queue this push is.
        // It is read once and that same value goes on the wire and settles the shadow —
        // reading it again afterwards would let a profile switch in between put this
        // cycle's patches under one profile and their shadow under another.
        val active = phases.localStorage("sync.scope.read", "read the active sync scope") {
            scopeProvider.current.first()
        }
            .getOrElse { return Result.failure(it.toAppError()) }

        // Read before the request, and inside a guard: this used to be the one line
        // between setting the status and entering the try, so a database that could not
        // be read left the engine advertising a push that was never attempted.
        //
        // No scope becomes an empty queue rather than a branch of its own, so that
        // "nothing of this owner's to send" has exactly one answer. Reading past it
        // would put one account's queued work in another account's request
        // (REQ-UA-019), and a push with no profile id has nothing to attribute on the
        // server either.
        val pending = active
            ?.let { scope ->
                phases.localStorage("sync.outbox.read", "read the pending changes") {
                    outboxDao.getPending(nowMillis, scope.ownerId)
                }
            }
            ?.getOrElse { return Result.failure(it.toAppError()) }
            ?: emptyList()

        if (pending.isEmpty()) return Result.success(null)

        // Decoded inside a guard for the same reason the read above is, and the pairing
        // is the point: one step on the outbox was guarded and the next was not, so a
        // single corrupt payload row threw out of `planPush` with `_status` already set
        // to `Pushing`. `runCycleCatching` caught it above the phase reporter, so
        // nothing moved the status back and `isRunning()` kept answering true — the sync
        // screen showed a push that had been failing on the same bytes forever, with no
        // dead letter and nothing to retry against.
        //
        // Reported rather than skipped: a row that will not decode is a storage defect,
        // not a remote refusal, and `localStorage` is the name that says so.
        //
        // The guard spans building the plan as well as decoding, because `getPending`
        // is only one of the ways out of here and every one of them strands the status
        // the same way. It also keeps the exits at four: the decode failure rides the
        // last return rather than adding a fifth.
        val plan = phases.localStorage("sync.outbox.decode", "assemble the queued changes") {
            val patches = pending.map { entity -> json.decodeFromString<DeltaPatch>(entity.payload) }
            PushPlan(
                pending = pending,
                patches = patches,
                request = BatchPushRequest(
                    deviceId = idGenerator.next(),
                    profileId = active?.profileId.orEmpty(),
                    patches = patches,
                ),
                active = active,
            )
        }

        // `fold` rather than `getOrElse`: this function answers with a Result either way, and
        // `getOrElse` would have to hand back a PushPlan to say "there is none", which is
        // the ambiguity the nullable return exists to avoid.
        return plan.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure<PushPlan?>(it.toAppError()) },
        )
    }

    /**
     * Pushes all pending patches to the server.
     */
    internal suspend fun push(): Result<PushSummary> {
        val session = authRepository.currentSession.value
        val signedIn = session is Session.SignedIn
        if (signedIn) _status.value = SyncEngineStatus.Pushing

        // One exit for "there is nothing to push", whether that is because nobody is
        // signed in or because the queue is empty. They are the same answer — an empty
        // summary over an untouched outbox — and splitting them across two returns cost
        // the budget the discard below needs.
        val plan = if (signedIn) {
            planPush().getOrElse { return phases.pushFailed(it.toAppError(), 0) }
        } else {
            null
        }
        if (plan == null) {
            // Idle in the signed-out case too, which is a no-op: no push is in flight,
            // because the only thing that sets Pushing is three lines above.
            _status.value = SyncEngineStatus.Idle
            return Result.success(PushSummary(0, 0, 0))
        }
        val pending = plan.pending
        val patches = plan.patches
        val active = plan.active

        return try {
            val response = api.batchPush(plan.request)

            // REQ-UA-018: is the account that authorised this request still the one
            // signed in? Compared by account, not by session — see ADR
            // 2026-10-05-a-push-response-is-matched-by-account-not-by-session, and the
            // token-refresh case that decides it. `null` means there is no account to
            // match, and that is deliberately not a match.
            val requestedBy = session.accountIdOrNull
            val nowSignedInAs = authRepository.currentSession.value.accountIdOrNull

            if (requestedBy != nowSignedInAs) {
                // The rows stay queued, the shadow keeps its in-flight marker, and the
                // server's answer is not acted on. Applying it is the defect #181
                // describes: the previous account's work marked delivered on behalf of
                // whoever is signed in now, with the device and the server disagreeing
                // and nothing left to say so. Nothing is lost by discarding — the row is
                // still in the outbox and the next cycle under an account entitled to
                // send it delivers it, which the test class checks end to end.
                log.e {
                    "Push response discarded: requested under [$requestedBy], " +
                        "now [$nowSignedInAs]. ${pending.size} row(s) stay queued."
                }
                val discarded = PushSummary(0, 0, 0, discarded = response.results.size)
                _lastPush.value = Result.success(discarded)
                _status.value = SyncEngineStatus.Idle
                return Result.success(discarded)
            }

            var succeeded = 0
            var failed = 0
            var lost = 0

            response.results.forEach { result ->
                val patch = patches.firstOrNull { it.patchId == result.patchId }
                // `lost` is checked before `ok` because the server reports a lost race
                // as `ok: true`. Branching on `ok` first is the original defect: the
                // outbox row was deleted and the shadow settled as confirmed, promoting
                // a state the server never took, and the next diff was computed against
                // that fiction. #203, REQ-OS-026.
                if (result.ok && result.lost) {
                    lost++
                    // The queued change is not left in place: the server has refused it
                    // on per-field LWW grounds, so re-sending the identical patch would
                    // lose identically. Keeping it would block every patch behind it.
                    outboxDao.delete(result.patchId)
                    if (active != null && patch != null) {
                        resolveLostRace(patch, scope = active)
                    }
                } else if (result.ok) {
                    outboxDao.delete(result.patchId)
                    if (active != null && patch != null) {
                        // The version the server just reported, recorded on the same
                        // guarded statement that promotes the state — so a superseded
                        // patch's response cannot write a version for a state that was
                        // never its own to settle.
                        settleShadow(
                            patch,
                            applied = true,
                            scope = active,
                            serverVersion = result.newVersion,
                        )
                    }
                    succeeded++
                } else {
                    failed++
                    if (result.isRetriable) {
                        deferOrDeadLetter(pending.firstOrNull { it.patchId == result.patchId }, result)
                    } else {
                        // The server will never accept this patch. Keeping it would
                        // block every patch behind it, forever. Releasing the shadow
                        // marker is what makes the next local edit re-send the
                        // fields this one was carrying, instead of diffing against a
                        // state the server never reached.
                        outboxDao.delete(result.patchId)
                        if (active != null && patch != null) {
                            settleShadow(patch, applied = false, scope = active)
                        }
                    }
                }
            }

            val summary = PushSummary(response.results.size, succeeded, failed, lost = lost)
            _lastPush.value = Result.success(summary)
            _status.value = SyncEngineStatus.Idle
            Result.success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The transport names its own failures — `AppError.Network` for anything
            // that went wrong on the wire, with the cause attached — and that is
            // exactly the distinction this cycle needs: "the server did not take it"
            // and "this device could not read its own outbox" are different problems
            // with different fixes, and a user told the wrong one retries the wrong
            // thing. Anything arriving here unnamed came from our code, and is
            // reported as such *with* its cause: the previous flattening to
            // `AppError.Unknown(e.message ?: "")` dropped the stack trace at the one
            // point where it is the only thing that would have said what went wrong.
            phases.pushFailed(e.toAppError(), pending.size)
        }
    }

    /**
     * Backs a rejected patch off, or gives up on it.
     *
     * The count lives in SQL (`attempts = attempts + 1`) and is read back before
     * the decision, so the increment and the decision cannot disagree when two
     * cycles overlap.
     *
     * Giving up means *moving* the patch, never deleting it: a change the user made
     * should not be destroyed because the system could not deliver it.
     */
    private suspend fun deferOrDeadLetter(entity: SyncOutboxEntity?, result: PatchResult) {
        if (entity == null) return

        val attempts = (outboxDao.attemptsOf(entity.patchId) ?: entity.attempts) + 1
        val reason = result.error ?: "Unknown error"

        if (retryPolicy.isExhausted(attempts)) {
            // The patch is set aside, not lost — see SyncDeadLetterEntity. Its in-flight
            // shadow marker is released with it, so the confirmed state stays at what
            // the server really has and the next local edit re-sends these fields.
            scopeProvider.current.first()?.let { active ->
                json.decodeFromString<DeltaPatch>(entity.payload).let { patch ->
                    shadowDao.release(
                        ownerId = active.ownerId,
                        profileId = active.profileId,
                        entityType = patch.entityType.key,
                        entityId = patch.entityId,
                        patchId = patch.patchId,
                    )
                }
            }
            deadLetterDao.insert(
                SyncDeadLetterEntity(
                    patchId = entity.patchId,
                    // Carried over from the row being shelved, rather than read from a
                    // scope now: the patch is set aside because of what happened to it,
                    // so it stays that patch's, and a profile that moved in between must
                    // not file it under the wrong account.
                    ownerId = entity.ownerId,
                    entityId = entity.entityId,
                    entityType = entity.entityType,
                    payload = entity.payload,
                    createdAt = entity.createdAt,
                    failedAt = now(),
                    attempts = attempts,
                    lastError = reason,
                ),
            )
            outboxDao.delete(entity.patchId)
            log.e {
                "Patch ${entity.patchId} failed $attempts times; moved to the " +
                    "dead letter store [error=$reason]"
            }
            return
        }

        val delay = retryPolicy.delayFor(attempts)
        outboxDao.markFailed(
            id = entity.patchId,
            error = reason,
            nextAttemptAt = now() + delay,
        )
        log.w { "Patch ${entity.patchId} failed (attempt $attempts); retrying in ${delay}ms" }
    }

    /** What one pulled event did, from the loop's point of view. */
    private sealed interface PullStep {
        /** Applied cleanly — the cursor may advance past it. */
        data object Done : PullStep

        /** Another profile's event. Skipped, and the cursor still advances. */
        data object Skipped : PullStep

        /**
         * Applied by no means, and never will be: the payload was absent or was not a
         * document. The cursor advances — the same bytes will arrive again, and blocking
         * here would end the account's sync over one unusable row — but the event is
         * counted as dropped and the reason is logged.
         *
         * This arm is what stops an event with no payload being reported as applied.
         * It used to return `Applied`, so the server considered it delivered, the client
         * considered it done, and the row was never written again.
         */
        data class AppliedButUnusable(val reason: String) : PullStep

        /**
         * Not applied, and a later attempt might succeed. The cursor stays here, so the
         * event is delivered again next cycle.
         *
         * Distinct from [Skipped] and [AppliedButUnusable] because the two of those
         * need opposite cursor treatment, and conflating them is a stall: the log
         * interleaves every profile of the account, so an unhandled *other-profile*
         * event would freeze this account's sync at that position forever.
         */
        data object Unappliable : PullStep
    }

    /**
     * Applies one event to [scope], or says why it was not applied.
     *
     * Extracted so the pull loop is accounting only. The distinction it draws — skip,
     * stall, apply — is the part worth reading on its own.
     */
    private suspend fun applyEvent(event: SyncEvent, scope: SyncScope): PullStep {
        if (!event.belongsTo(scope)) return PullStep.Skipped
        val handler = handlers[event.entityType] ?: return PullStep.Unappliable
        return when (val outcome = handler.apply(event)) {
            is ApplyOutcome.Applied -> PullStep.Done
            is ApplyOutcome.Skipped -> PullStep.AppliedButUnusable(outcome.reason)
            is ApplyOutcome.Failed -> PullStep.Unappliable
        }
    }

    /**
     * Pulls events from the server for [scope], starting after the cursor stored for
     * that scope. The caller passes the starting position so the value it applies is
     * the value it read — re-reading the cursor inside would allow a profile switch
     * between the read and the write to store one scope's position under another.
     */
    private suspend fun pull(scope: SyncScope, sinceLsn: Long): Result<PullSummary> {
        val session = authRepository.currentSession.value
        if (session !is Session.SignedIn) {
            return Result.success(PullSummary(0, 0, 0))
        }

        _status.value = SyncEngineStatus.Pulling

        return try {
            var received = 0
            var applied = 0
            var dropped = 0
            var maxLsn = sinceLsn
            var stalled: String? = null
            // The position the *next* request reads from. Distinct from maxLsn, which
            // is the position that was actually dealt with and is the one stored: if a
            // page ends and the next request resumes from maxLsn, a page that was
            // fetched but not fully applied would be fetched again — which is correct —
            // and a page that was fully applied would be fetched twice, which is not.
            var readFrom = sinceLsn
            // Whether a page came back exactly full, which is the only signal a
            // client-only loop has that more may exist. Never treated as "there is
            // more"; only as "we cannot tell", which is what gets said out loud.
            var lastPageFull = false

            // Four ways out of a loop, each with a reason, reads as four reasons to
            // forget one. The guard is a condition and the exits are statements, so
            // every way the loop can end is visible in one place.
            var keepGoing = true
            while (keepGoing) {
                val page = api.getEventsSince(readFrom, limit = PULL_PAGE_SIZE)
                if (page.isEmpty()) {
                    keepGoing = false
                } else {
                    received += page.size
                    lastPageFull = page.size >= PULL_PAGE_SIZE

                    val outcome = applyPage(page, scope)
                    applied += outcome.applied
                    dropped += outcome.dropped
                    maxLsn = maxOf(maxLsn, outcome.maxLsn)
                    stalled = outcome.stalled

                    // Resume after the last thing this page carried, which is the whole
                    // page: everything in it was dealt with.
                    val next = page.maxOf { it.serverLsn }
                    if (next <= readFrom) {
                        // No progress. The server answered with events at or before
                        // the position asked from, so asking again returns the same
                        // page. This is a loop that would otherwise never end, and it
                        // is the one failure mode a client-only pagination adds that a
                        // single request could not have.
                        log.w {
                            "The feed returned no position past lsn=$readFrom; " +
                                "stopping rather than asking again for the same page"
                        }
                        keepGoing = false
                    } else {
                        readFrom = next
                        // A short page is the end of the feed. A full one is not proof
                        // of anything either way, so the loop asks once more and the
                        // emptiness of that answer is the proof.
                        keepGoing = stalled == null && lastPageFull
                    }
                }
            }

            if (lastPageFull && stalled == null) {
                log.d {
                    "A page of exactly $PULL_PAGE_SIZE events arrived; the feed may hold " +
                        "more, and a client-only loop cannot tell. The next cycle asks again."
                }
            }

            // Persist the server LSN so the next pull resumes from this point. Written
            // after the loop, so it is the last position that was actually applied, and
            // against the scope that produced it — a cursor written to whichever scope
            // happens to be current is how two profiles end up sharing a position.
            stateRepository.setLastLsn(scope, maxLsn)
            // Stamp lastSuccessfulSyncAt so the UI "Last synced" field stays current.
            stateRepository.recordSuccessfulSync(scope, now())

            val summary = PullSummary(received, applied, dropped)

            // A cycle that stopped early is not a cycle that finished. Reporting it as
            // a success stamped "last synced" on a device that is now stuck behind an
            // event it will re-receive on every subsequent cycle and never apply — the
            // symptom was a sync that looked healthy and did nothing.
            if (stalled != null) {
                val error = AppError.Persistence(
                    "Sync stopped: $stalled. The events after it will keep arriving, " +
                        "and this device will not apply them until that one can be.",
                    code = "sync.pull_stalled",
                )
                _lastPull.value = Result.failure(error)
                _status.value = SyncEngineStatus.Failure(error)
                return Result.failure(error)
            }

            _lastPull.value = Result.success(summary)
            _status.value = SyncEngineStatus.Idle
            Result.success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Same reasoning as the push: the transport's own `AppError.Network` is
            // kept, and an unnamed failure keeps its cause rather than its class name
            // standing in for one.
            phases.pullFailed(e.toAppError(), sinceLsn)
        }
    }

    /**
     * Returns the row to the state the server holds, and drops the marker that would
     * otherwise re-send the edit that lost.
     *
     * ## Where the server's state comes from
     *
     * Not from the response. `PatchResult.serverState` exists in the client model and
     * the server never populates it — `sync_batch_apply` returns `patchId`, `ok`,
     * `cached`, `lost`, `legacy` and `newVersion`, and nothing else. The decision to
     * adopt the server's state was therefore not executable as first worded, and the
     * fact the server does hold is one this device already has: the shadow's
     * `confirmed_json`, "serialised entity state the server is known to hold". A lost
     * patch changed nothing on the server, so that row is still true.
     *
     * ## Why the revert precedes the release
     *
     * `SyncPatchBuilder` diffs against `inFlightJson ?: confirmedJson`. Releasing the
     * marker first and failing to revert would make the next diff compare the losing
     * edit against confirmed state, regenerate it, and lose it again — forever. Doing
     * it in this order, and releasing only on success, is what makes the fourth
     * scenario ("the next change is built on the server's state") an empty diff rather
     * than a loop. A silent loop is a worse defect than the silent loss it replaces.
     *
     * ## What happens when the revert fails
     *
     * The marker is deliberately left in place. The queued edit stays owned by the
     * shadow, so nothing regenerates it, and the loss is logged with the reason rather
     * than being resolved into a state neither the server nor the device holds. The
     * push summary counts this as a loss either way — the server did refuse the edit,
     * and that is true whatever this device managed to do about it afterwards.
     */
    private suspend fun resolveLostRace(patch: DeltaPatch, scope: SyncScope) {
        val typeKey = patch.entityType.key
        val confirmed = shadowDao
            .get(scope.ownerId, scope.profileId, typeKey, patch.entityId)
            ?.confirmedJson

        val reverted = confirmed?.let { document ->
            runCatchingCancellable {
                val json = StableJson.parseToJsonElement(document).jsonObject
                writerProvider().upsert(patch.entityType, json)
            }.onFailure { e ->
                log.e(e) {
                    "Patch ${patch.patchId} lost its race and the row could not be " +
                        "returned to the server's state; the edit is left un-sent"
                }
            }.isSuccess
        } ?: true

        if (reverted) {
            settleShadow(patch, applied = false, scope = scope)
        }
    }

    private suspend fun settleShadow(
        patch: DeltaPatch,
        applied: Boolean,
        scope: SyncScope,
        serverVersion: Long? = null,
    ) {
        val typeKey = patch.entityType.key
        if (applied) {
            shadowDao.confirm(
                ownerId = scope.ownerId,
                profileId = scope.profileId,
                entityType = typeKey,
                entityId = patch.entityId,
                patchId = patch.patchId,
                json = shadowDao.get(scope.ownerId, scope.profileId, typeKey, patch.entityId)
                    ?.inFlightJson ?: return,
                serverVersion = serverVersion,
            )
        } else {
            shadowDao.release(
                ownerId = scope.ownerId,
                profileId = scope.profileId,
                entityType = typeKey,
                entityId = patch.entityId,
                patchId = patch.patchId,
            )
        }
    }

    /**
     * One page of the feed, dealt with, and what it cost.
     *
     * Split out of [pull] because the loop and the classification are two different
     * questions and the second is the one worth reading on its own: for each event,
     * what it means for the cursor. A page that stops early says so in [stalled]
     * rather than returning, because the caller has a cursor and a summary to settle
     * and must not settle them twice.
     */
    private suspend fun applyPage(page: List<SyncEvent>, scope: SyncScope): PageOutcome {
        var applied = 0
        var dropped = 0
        var maxLsn = 0L
        var stalled: String? = null

        for (event in page) {
            when (val step = applyEvent(event, scope)) {
                PullStep.Done -> {
                    applied++
                    maxLsn = maxOf(maxLsn, event.serverLsn)
                }

                PullStep.Skipped -> {
                    // Another profile's event, in a feed that is per *owner*. The cursor
                    // advances past it: the log interleaves every profile of the
                    // account, so treating this as "not applicable" would freeze the
                    // cursor at the first one and the account would never sync anything
                    // again. It is counted as dropped so the summary is honest about
                    // what arrived.
                    dropped++
                    maxLsn = maxOf(maxLsn, event.serverLsn)
                }

                is PullStep.AppliedButUnusable -> {
                    // Never applicable, so never retried — but also never *applied*, and
                    // the two must not be reported the same way. Advancing the cursor is
                    // correct; calling this a success is what made the loss silent.
                    dropped++
                    maxLsn = maxOf(maxLsn, event.serverLsn)
                    log.w {
                        "Skipping unusable event at lsn=${event.serverLsn}: ${step.reason}; " +
                            "cursor advances, the change is not applied"
                    }
                }

                PullStep.Unappliable -> {
                    // The cursor does NOT advance past this event, and the page stops
                    // here. The previous code did `maxLsn = maxOf(maxLsn, lsn)` first
                    // and then `?: return@forEach`, so an event whose type this client
                    // cannot handle advanced the cursor anyway and was never applied
                    // again: the server considered it delivered, the client considered
                    // it done, and the data was gone. The only trace was a pull summary
                    // that said it had received the event and applied nothing.
                    dropped++
                    stalled = "an event this client cannot apply is at lsn=${event.serverLsn}"
                    log.w {
                        "Dropping event at lsn=${event.serverLsn} for unknown type " +
                            "${event.entityType.key}; cursor stays at $maxLsn"
                    }
                    break
                }
            }
        }
        return PageOutcome(applied, dropped, maxLsn, stalled)
    }

    /** What one page of the feed cost, and whether it stopped early. */
    private data class PageOutcome(val applied: Int, val dropped: Int, val maxLsn: Long, val stalled: String?)
}
