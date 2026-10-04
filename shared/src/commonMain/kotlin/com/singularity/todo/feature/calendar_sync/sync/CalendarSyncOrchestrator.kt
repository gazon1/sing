package com.singularity.todo.feature.calendar_sync.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Singleton orchestrator for calendar sync triggers.
 *
 * Owns a [Channel] that collects incoming [SyncSource] triggers, debounces them by
 * 1 second, picks the strongest via [SyncSource.upgrade], and hands off to
 * [CalendarSyncWorkScheduler] only when the [DirtyHashProvider] reports a meaningful
 * state change.
 *
 * ## Responsibilities
 *
 * 1. **Coalesce bursts** — rapid `TaskDirty` triggers (e.g. user edits 5 tasks in
 *    quick succession) are debounced to a single sync.
 * 2. **Deduplicate redundant syncs** — if the task/calendar state hasn't changed since
 *    the last handoff (same [DirtyHashProvider.hash]), skip scheduling.
 * 3. **Prioritise triggers** — `Manual` / `ConfigChanged` beat `TaskDirty` / `Periodic`.
 * 4. **Own indicator state** — [pendingSource] is surfaced to UI so it can show a
 *    transient "Syncing…" indicator without touching [CalendarSyncWorkScheduler].
 *
 * ## Lifecycle
 *
 * Created as a Koin `single`. Call [start] once from `Application.onCreate`
 * to launch the collector coroutine on the app scope. It is cancelled when the
 * [CoroutineScope] (application lifecycle) is cancelled.
 *
 * @param scheduler WorkManager scheduler.
 * @param scope Application-scoped coroutine scope.
 * @param dirtyHashProvider Provider of the current state hash.
 */
class CalendarSyncOrchestrator(
    private val scheduler: CalendarSyncWorkScheduler,
    private val scope: CoroutineScope,
    private val dirtyHashProvider: DirtyHashProvider,
    private val logger: Logger = Logger.withTag("CalendarSync"),
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
) {

    /**
     * Guards [start] against a second registration. Set before the coroutine is
     * launched, so two racing callers cannot both get past the check.
     */
    private var started = false

    /**
     * Incoming trigger channel. CONFLATED: callers never block and only the
     * strongest pending trigger survives a paused consumer (bounded memory —
     * see 2026-09-23-tech-debt-audit item 7). Collected by [start].
     */
    private val channel = Channel<SyncSource>(Channel.CONFLATED)

    /**
     * The strongest pending source accumulated since the last handoff.
     * Reset to `null` after WorkManager is enqueued.
     */
    private val _pendingSource = MutableStateFlow<SyncSource?>(null)
    val pendingSource: StateFlow<SyncSource?> = _pendingSource.asStateFlow()

    /** Hash of the last state handed off to WorkManager. 0 means "never handed off". */
    private var lastHandedOffHash: Long = 0L

    /**
     * Starts the debounced collector coroutine.
     *
     * ## Why each pass is guarded
     *
     * The collector runs on an application-scoped background dispatcher with no
     * `CoroutineExceptionHandler`. A throw from the body therefore reaches the global
     * handler and kills the process — and, worse in the non-fatal case, silently kills
     * the collector, leaving calendar sync dead for the rest of the process lifetime
     * with no symptom at all. The body reads `dirtyHashProvider.hash()`, three Room
     * `.first()` calls, so an IO or SQLite failure is an ordinary event, not an
     * exceptional one. Each pass is therefore caught and reported, and the collector
     * survives to serve the next trigger.
     *
     * ## Idempotency
     *
     * Repeated calls are no-ops after the first, enforced by [started] rather than
     * assumed. Without the guard, a second [start] would call `consumeAsFlow()` on an
     * already-consumed channel and throw `IllegalStateException` — so this is a
     * behavioural guarantee, not just an optimization.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            channel.consumeAsFlow()
                .debounce(1_000L)
                .collect { strongest ->
                    runCatching {
                        val currentHash = dirtyHashProvider.hash()
                        if (currentHash == lastHandedOffHash) {
                            _pendingSource.value = null
                            return@runCatching
                        }
                        scheduler.enqueueSync()
                        lastHandedOffHash = currentHash
                        _pendingSource.value = null
                    }.onFailure { error ->
                        logger.e(error) { "Calendar sync collector pass failed (source=$strongest)" }
                        crashReporter.report(error, "calendar_sync.collector_failed")
                    }
                }
        }
    }

    /**
     * Requests a sync with the given [source].
     *
     * Uses [Channel.trySend] so callers never suspend — fire-and-forget.
     * The trigger is debounced by [start]'s collector.
     */
    fun requestSync(source: SyncSource) {
        val updated = _pendingSource.value?.upgrade(source) ?: source
        _pendingSource.value = updated
        channel.trySend(source)
    }
}
