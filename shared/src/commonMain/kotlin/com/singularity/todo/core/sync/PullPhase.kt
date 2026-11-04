package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

/**
 * Pull phase of the sync cycle.
 *
 * Polls the server's feed for new events for the active sync scope, applies them
 * via registered handlers, and reports outcomes via [SyncPhaseReporter].
 */
internal class PullPhase(
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val stateRepository: SyncStateRepository,
    private val clock: Clock,
    private val scopeProvider: SyncScopeProvider,
    private val phases: SyncPhaseReporter,
    private val getHandlers: () -> Map<DocType, EntityApply>,
    private val scope: AutoCloseableCoroutineScope,
    /**
     * Merges the HLC received from another device into the local clock.
     *
     * Called on every pulled event that carries an HLC. Without this, a device whose
     * wall clock is behind never catches up: every subsequent local patch loses every
     * field conflict to the server because the local HLC is still behind.
     *
     * @see HlcFactory.tock()
     * @see <a href="https://github.com/gazon1/sing/issues/179">GH #179</a>
     */
    private val hlcFactory: HlcFactory,
    /**
     * The shadow of what the server holds, kept current on pull so a lost-race
     * revert restores the server's state (and its claims) rather than a stale
     * snapshot. Nullable so callers that do not need the shadow (tests) can omit it.
     */
    private val shadowDao: SyncShadowDao? = null,
    /**
     * The shelf of things that gave up.
     *
     * Used by the pull path for one case only: an event whose payload is present but
     * cannot be read. The cursor advances past it — the server sends the same bytes on
     * every delivery — and the event is recorded here, so a remote change this device
     * dropped is a row somebody can find rather than a log line nobody reads.
     *
     * Not nullable, and the default is [NoDeadLetterShelf] rather than `null`: a null
     * default made the omission silent, so the recording half of this feature could be
     * compiled away at a call site and a corrupt payload would leave no trace but a log
     * line. The stand-in logs an error, by name, for every write it is asked for.
     *
     * A default rather than a required argument because the pull loop is exercised on
     * its own, with no database behind it. The production wiring does not rely on it:
     * [SyncEngine] takes the shelf as a required constructor argument and passes it on,
     * so the engine cannot be built without one.
     */
    private val deadLetterDao: SyncDeadLetterDao = NoDeadLetterShelf,
) {
    private val log = Logger.withTag("PullPhase")

    /**
     * What one pulled event did, from the loop's point of view.
     */
    private sealed interface PullStep {
        /** Applied cleanly — the cursor may advance past it. */
        data object Done : PullStep

        /** Another profile's event. Skipped, and the cursor still advances. */
        data object Skipped : PullStep

        /**
         * Applied by no means, and never will be, and nothing was misread: the event
         * carried **no** document at all.
         *
         * This arm is narrower than it used to be, and the narrowing is the point. It
         * used to take a payload that "was absent or was not a document". A payload that
         * is *present* and is not a document is [Corrupt] now, because bytes arrived and
         * could not be read — [PullEventHandler] returns `Skipped(corrupt = true)` for
         * it. Only the absent case is left here, and the difference is observable: only
         * [Corrupt] leaves a row on the shelf.
         *
         * A new non-corrupt skip reason lands **here**, not in [Corrupt]: an event is
         * shelved only when the classifier says the bytes themselves were unreadable.
         *
         * The cursor advances — the same bytes will arrive again, and blocking here
         * would end the account's sync over one unusable row — but the event is counted
         * as dropped and the reason is logged.
         */
        data class AppliedButUnusable(val reason: String) : PullStep

        /**
         * The payload was **present and unreadable**: it did not cast to a document, or
         * it did not decode into one.
         *
         * Both halves of that sentence matter, and the first one is narrower than it
         * looks. "Present" excludes the event the server sent with no document at all:
         * that one is [AppliedButUnusable], because nothing arrived that could have been
         * misread — there is nothing to record and nothing to point at (see
         * [PullEventHandler] and the `an event with no document is not shelved` test).
         * "Did not cast to a document" means exactly one thing here: the payload was
         * there and was not a `JsonObject`.
         *
         * The cursor advances, for the same reason as [AppliedButUnusable] — the server
         * sends the same bytes on every delivery — and the event is recorded in the
         * dead-letter store. The recording is the difference: a corrupt payload is a
         * remote change this device dropped, and a dropped remote change is a
         * divergence between the server's state and this device's.
         *
         * The recording is capped at [PULL_SHELF_MAX_ROWS] rows per account, and the
         * trim runs once per page rather than once per corrupt event. Unlike the
         * push-side shelf, this producer is driven by the server: a feed that is corrupt
         * wholesale would otherwise write the whole feed's payloads into SQLite, one row
         * per LSN, and the settings count and list would grow without bound on a phone.
         */
        data class Corrupt(val reason: String) : PullStep

        /**
         * Not applied, and a later attempt might succeed. The cursor stays here, so the
         * event is delivered again next cycle.
         */
        data object Unappliable : PullStep
    }

    /**
     * Applies one event to [scope], or says why it was not applied.
     *
     * ## Why HLC merging is done here rather than in the handler
     *
     * The handler is entity-specific and registered per DocType. The clock merge is
     * a global operation that belongs to the sync loop, not to individual entity handlers.
     * Merging here keeps every event on the same global clock timeline.
     */
    private suspend fun applyEvent(
        event: SyncEvent,
        scope: SyncScope,
    ): PullStep {
        if (!event.belongsTo(scope)) return PullStep.Skipped
        // Merge the received HLC into the local clock so the local device advances to
        // meet the remote clock. Without this, a device behind a day never catches up
        // and loses every field conflict silently (#179).
        event.hlc?.let { hlcFactory.tock(it) }
        val handler = getHandlers()[event.entityType] ?: return PullStep.Unappliable
        return when (val outcome = handler.apply(event)) {
            is ApplyOutcome.Applied -> {
                refreshShadow(event, scope)
                PullStep.Done
            }

            is ApplyOutcome.Skipped -> {
                if (outcome.corrupt) {
                    PullStep.Corrupt(outcome.reason)
                } else {
                    PullStep.AppliedButUnusable(outcome.reason)
                }
            }

            is ApplyOutcome.Failed -> {
                PullStep.Unappliable
            }
        }
    }

    /**
     * Keeps the shadow current with the server's state (and its claims) for an
     * entity that was just pulled, so a later lost-race revert restores what
     * the server holds rather than a stale snapshot.
     *
     * Only CREATED/UPDATED/RESTORED events carry a document; DELETED and events
     * without data leave the shadow untouched. A missing shadow means the entity
     * was never pushed by this device, and one is inserted so the first push
     * diffs against the server's state instead of an empty base.
     *
     * The in-flight marker (if any) is preserved: only `confirmed_json` moves.
     */
    private suspend fun refreshShadow(
        event: SyncEvent,
        scope: SyncScope,
    ) {
        val dao = shadowDao ?: return
        val data = event.data as? kotlinx.serialization.json.JsonObject ?: return
        if (event.eventType == SyncEventType.DELETED) return
        val json =
            com.singularity.todo.core.serialization.StableJson.encodeToString(
                kotlinx.serialization.json.JsonObject
                    .serializer(),
                data,
            )
        val updated =
            dao.updateConfirmed(
                ownerId = scope.ownerId,
                profileId = scope.profileId,
                entityType = event.entityType.key,
                entityId = event.entityId,
                json = json,
            )
        if (updated == 0) {
            dao.upsert(
                SyncShadowEntity(
                    ownerId = scope.ownerId,
                    profileId = scope.profileId,
                    entityType = event.entityType.key,
                    entityId = event.entityId,
                    confirmedJson = json,
                ),
            )
        }
    }

    /**
     * Pulls events from the server for [scope], starting after [sinceLsn].
     *
     * The caller reads and passes the cursor so the value applied is the value read —
     * re-reading inside would allow a profile switch between the read and write to store
     * one scope's position under another.
     */
    internal suspend fun pull(
        scope: SyncScope,
        sinceLsn: Long,
    ): PhaseResult<PullSummary> {
        val session = authRepository.currentSession.value
        if (session !is Session.SignedIn) {
            phases.setStatus(SyncEngineStatus.Idle)
            return PhaseResult.NotRun
        }

        phases.setStatus(SyncEngineStatus.Pulling)
        return pullWithSession(scope, sinceLsn)
    }

    /**
     * Runs the paginated pull loop for a signed-in session.
     * Returns [PhaseResult.Failed] on any error (stalled event, network exception).
     */
    private suspend fun pullWithSession(
        scope: SyncScope,
        sinceLsn: Long,
    ): PhaseResult<PullSummary> =
        try {
            when (val result = pullPages(scope, sinceLsn)) {
                is PageResult.Ok -> {
                    phases.setStatus(SyncEngineStatus.Idle)
                    PhaseResult.Ok(result.summary)
                }

                is PageResult.Stalled -> {
                    PhaseResult.Failed(result.error)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = e.toAppError()
            phases.pullFailed(error, sinceLsn)
            PhaseResult.Failed(error)
        }

    /**
     * Executes the pagination loop and returns the pull outcome.
     * On a [PageResult.Stalled], records the failure and returns [PhaseResult.Failed].
     */
    private suspend fun pullPages(
        scope: SyncScope,
        sinceLsn: Long,
    ): PageResult {
        var maxLsn = sinceLsn
        var readFrom = sinceLsn
        var lastPageFull = false
        var stalled: String? = null
        var received = 0
        var applied = 0
        var dropped = 0

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

                val next = page.maxOf { it.serverLsn }
                if (next <= readFrom) {
                    log.w {
                        "The feed returned no position past lsn=$readFrom; " +
                            "stopping rather than asking again for the same page"
                    }
                    keepGoing = false
                } else {
                    readFrom = next
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

        // Persist the server LSN so the next pull resumes from this point.
        stateRepository.setLastLsn(scope, maxLsn)
        stateRepository.recordSuccessfulSync(scope, clock.now().toEpochMilliseconds())

        if (stalled != null) {
            val error =
                AppError.Persistence(
                    "Sync stopped: $stalled. The events after it will keep arriving, " +
                        "and this device will not apply them until that one can be.",
                    code = "sync.pull_stalled",
                )
            phases.recordPullResult(Result.failure(error))
            phases.setStatus(SyncEngineStatus.Failure(error))
            return PageResult.Stalled(error, received, applied, dropped)
        }

        val summary = PullSummary(received, applied, dropped)
        phases.recordPullResult(Result.success(summary))
        return PageResult.Ok(summary, maxLsn)
    }

    /** Result of [pullPages]. */
    private sealed class PageResult {
        data class Ok(val summary: PullSummary, val maxLsn: Long) : PageResult()

        data class Stalled(val error: AppError, val received: Int, val applied: Int, val dropped: Int) : PageResult()
    }

    /**
     * One page of the feed, dealt with, and what it cost.
     */
    private suspend fun applyPage(
        page: List<SyncEvent>,
        scope: SyncScope,
    ): PageOutcome {
        var applied = 0
        var dropped = 0
        var shelved = 0
        var maxLsn = 0L
        var stalled: String? = null

        for (event in page) {
            when (val step = applyEvent(event, scope)) {
                PullStep.Done -> {
                    applied++
                    maxLsn = maxOf(maxLsn, event.serverLsn)
                }

                PullStep.Skipped -> {
                    dropped++
                    maxLsn = maxOf(maxLsn, event.serverLsn)
                }

                is PullStep.AppliedButUnusable -> {
                    dropped++
                    maxLsn = maxOf(maxLsn, event.serverLsn)
                    log.w {
                        "Skipping unusable event at lsn=${event.serverLsn}: ${step.reason}; " +
                            "cursor advances, the change is not applied"
                    }
                }

                is PullStep.Corrupt -> {
                    dropped++
                    shelved++
                    maxLsn = maxOf(maxLsn, event.serverLsn)
                    recordCorruptEvent(event, scope, step.reason)
                    log.w {
                        "Skipping an undecodable event at lsn=${event.serverLsn}: ${step.reason}; " +
                            "cursor advances, the change is not applied, the event is shelved"
                    }
                }

                PullStep.Unappliable -> {
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
        // Once per page, not once per corrupt event. A page that is corrupt wholesale
        // shelves every one of its events, and a trim per shelved event would put
        // PULL_PAGE_SIZE DELETE-and-count round trips — each its own transaction — on
        // the pull loop to arrive at the same cap one delete reaches at the end.
        if (shelved > 0) trimInboundShelf(scope)
        return PageOutcome(applied, dropped, maxLsn, stalled)
    }

    /**
     * Shelves an event this device could not read.
     *
     * The row carries the payload as it arrived — the bytes that could not be decoded
     * are the evidence — together with the reason the decode failed, and it belongs to
     * the account that received the event.
     *
     * ## Ageing, not jumping to the top
     *
     * The server sends the same bytes on every delivery, so a redelivery is the same
     * row, not a new one, and its `createdAt` and `failedAt` stay at the first sighting.
     * Rewriting them to `now` on every delivery would let a recurring corrupt event sit
     * at the newest end of `ORDER BY failed_at DESC` for ever, never ageing out of the
     * list and never being the row the cap drops. That is held structurally by
     * [SyncDeadLetterDao.insertInboundIfAbsent] — an insert that is ignored on conflict
     * keeps the row that is already there — rather than by reading the old row and
     * copying its timestamps back into a replace.
     *
     * `attempts` is the number of deliveries seen, not a retry count. Nothing retries an
     * inbound event — the bytes are identical every time — so this column says "how many
     * times this device was handed something it could not read", which is the useful
     * number, rather than `0`, which reads as a patch that never failed. The increment
     * is done by SQLite inside [SyncDeadLetterDao.bumpInboundDelivery], so it cannot be
     * lost to two pulls reading the same count and writing the same result — which is
     * exactly what a get-then-insert does under concurrency.
     *
     * A failure to write the row does not fail the pull. The event is lost either way,
     * and a storage error must not wedge the account's sync over one unreadable row —
     * the same reasoning that keeps a failed delete from being reported as applied.
     */
    private suspend fun recordCorruptEvent(
        event: SyncEvent,
        scope: SyncScope,
        reason: String,
    ) {
        val now = clock.now().toEpochMilliseconds()
        val key = SyncDeadLetterEntity.corruptEventId(ownerId = scope.ownerId, event = event)
        val payload =
            event.data?.let { StableJson.encodeToString(JsonElement.serializer(), it) } ?: ""
        val row =
            SyncDeadLetterEntity(
                patchId = key,
                ownerId = scope.ownerId,
                entityId = event.entityId,
                entityType = event.entityType.key,
                payload = payload,
                createdAt = now,
                failedAt = now,
                // A first sighting is one delivery. An ignored insert means the row is
                // already there, and the bump below counts this one on top of it.
                attempts = 1,
                lastError = reason,
            )
        runCatchingCancellable {
            val inserted = deadLetterDao.insertInboundIfAbsent(row)
            if (inserted == INSERT_IGNORED) deadLetterDao.bumpInboundDelivery(key, scope.ownerId)
        }.onFailure { e ->
            log.w(e) { "the undecodable event was not shelved: lsn=${event.serverLsn}" }
        }
    }

    /**
     * Drops this account's oldest unreadable events once they exceed
     * [PULL_SHELF_MAX_ROWS].
     *
     * Called once per page, after the events are dealt with, rather than from
     * [recordCorruptEvent]: a page that is corrupt wholesale shelves every one of its
     * events, and trimming after each of them would put one delete — each its own
     * transaction — per event on the pull loop to reach the same cap one delete reaches
     * at the end of the page.
     *
     * Per account, so one account's flood cannot evict another's evidence. A failure to
     * trim is logged and swallowed for the same reason a failure to shelve is: the shelf
     * is evidence, and a full shelf must not stop the account synchronising.
     */
    private suspend fun trimInboundShelf(scope: SyncScope) {
        runCatchingCancellable {
            val pruned = deadLetterDao.pruneInboundTo(scope.ownerId, PULL_SHELF_MAX_ROWS)
            if (pruned > 0) {
                log.w {
                    "the inbound shelf held more than $PULL_SHELF_MAX_ROWS unreadable " +
                        "event(s) for this account; $pruned of the oldest were dropped"
                }
            }
        }.onFailure { e ->
            log.w(e) { "the inbound shelf was not trimmed" }
        }
    }

    /** What one page of the feed cost, and whether it stopped early. */
    private data class PageOutcome(val applied: Int, val dropped: Int, val maxLsn: Long, val stalled: String?)
}

/**
 * What Room returns from an `@Insert` that its conflict strategy ignored: the row was
 * already there, so nothing was written.
 *
 * It is what lets [PullPhase.recordCorruptEvent] tell a redelivery from a first
 * sighting from the insert's own outcome — one statement, no read first — instead of
 * reading the row and writing it back, which two pulls can interleave into a lost count.
 */
private const val INSERT_IGNORED: Long = -1L
