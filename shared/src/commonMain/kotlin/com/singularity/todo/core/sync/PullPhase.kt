package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.sync.SyncPhaseReporter
import kotlinx.coroutines.CancellationException
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
         * Applied by no means, and never will be: the payload was absent or was not a
         * document. The cursor advances — the same bytes will arrive again, and blocking
         * here would end the account's sync over one unusable row — but the event is
         * counted as dropped and the reason is logged.
         */
        data class AppliedButUnusable(val reason: String) : PullStep

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
    private suspend fun applyEvent(event: SyncEvent, scope: SyncScope): PullStep {
        if (!event.belongsTo(scope)) return PullStep.Skipped
        // Merge the received HLC into the local clock so the local device advances to
        // meet the remote clock. Without this, a device behind a day never catches up
        // and loses every field conflict silently (#179).
        event.hlc?.let { hlcFactory.tock(it) }
        val handler = getHandlers()[event.entityType] ?: return PullStep.Unappliable
        return when (val outcome = handler.apply(event)) {
            is ApplyOutcome.Applied -> PullStep.Done
            is ApplyOutcome.Skipped -> PullStep.AppliedButUnusable(outcome.reason)
            is ApplyOutcome.Failed -> PullStep.Unappliable
        }
    }

    /**
     * Pulls events from the server for [scope], starting after [sinceLsn].
     *
     * The caller reads and passes the cursor so the value applied is the value read —
     * re-reading inside would allow a profile switch between the read and write to store
     * one scope's position under another.
     */
    internal suspend fun pull(scope: SyncScope, sinceLsn: Long): PhaseResult<PullSummary> {
        val session = authRepository.currentSession.value
        if (session !is Session.SignedIn) {
            phases.setStatus(SyncEngineStatus.Idle)
            return PhaseResult.NotRun
        }

        phases.setStatus(SyncEngineStatus.Pulling)

        return try {
            var received = 0
            var applied = 0
            var dropped = 0
            var maxLsn = sinceLsn
            var stalled: String? = null
            var readFrom = sinceLsn
            var lastPageFull = false

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

            val summary = PullSummary(received, applied, dropped)

            if (stalled != null) {
                val error = AppError.Persistence(
                    "Sync stopped: $stalled. The events after it will keep arriving, " +
                        "and this device will not apply them until that one can be.",
                    code = "sync.pull_stalled",
                )
                phases.recordPullResult(Result.failure(error))
                phases.setStatus(SyncEngineStatus.Failure(error))
                return PhaseResult.Failed(error)
            }

            phases.recordPullResult(Result.success(summary))
            phases.setStatus(SyncEngineStatus.Idle)
            return PhaseResult.Ok(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = e.toAppError()
            phases.pullFailed(error, sinceLsn)
            return PhaseResult.Failed(error)
        }
    }

    /**
     * One page of the feed, dealt with, and what it cost.
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
        return PageOutcome(applied, dropped, maxLsn, stalled)
    }

    /** What one page of the feed cost, and whether it stopped early. */
    private data class PageOutcome(
        val applied: Int,
        val dropped: Int,
        val maxLsn: Long,
        val stalled: String?,
    )
}
