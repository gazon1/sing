package com.singularity.todo.feature.calendar_sync.sync

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
) {

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
     * Safe to call multiple times — subsequent calls are no-ops after the first.
     */
    fun start() {
        scope.launch {
            channel.consumeAsFlow()
                .debounce(1_000L)
                .collect { strongest ->
                    val currentHash = dirtyHashProvider.hash()
                    if (currentHash == lastHandedOffHash) {
                        _pendingSource.value = null
                        return@collect
                    }
                    scheduler.enqueueSync()
                    lastHandedOffHash = currentHash
                    _pendingSource.value = null
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
