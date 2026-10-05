package com.singularity.todo.feature.calendar_sync.work

import com.singularity.todo.core.sync.DelayLoopSyncPeriodicTrigger
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * [GoogleSyncPeriodicTrigger] for platforms with no background job scheduler: the desktop
 * delay loop, driving [GoogleSyncCoordinator.syncNow].
 *
 * ## Why this wraps the sync core's loop instead of reusing it directly
 *
 * [DelayLoopSyncPeriodicTrigger] implements [com.singularity.todo.core.sync.SyncPeriodicTrigger],
 * which is a different interface with a different gate — it has no way to be told that the
 * thing it drives is currently unconfigured, because in the sync core "configured" means a
 * setting that the runner already watches. Google has no such watcher, so the gate has to
 * live in the request lambda, and that means a two-line class rather than a subclass:
 * [DelayLoopSyncPeriodicTrigger] is `internal` to `:shared` and final, so composing it is the
 * only option that leaves the sync core's own driver untouched.
 *
 * ## Why the loop outlives a failing cycle
 *
 * Inherited, not reimplemented: the loop's own guard swallows a throwing cycle so one network
 * error cannot kill auto-sync for the rest of the process. That matters more here than it does
 * for the sync core, because [isConfigured] reads a keystore and a DataStore on every cycle and
 * either can throw. The cycle is expected to fail sometimes; the loop is not allowed to notice.
 *
 * @param coordinatorProvider resolved **per cycle**, never captured. The coordinator is a
 *   per-profile factory that reads the active user when it is built, so a captured instance
 *   would sync whichever profile happened to be active when this trigger was created. This is
 *   the same reason the settings screen takes `eventSource` as a lambda in the DI module.
 * @param scope the loop's home; a graph-owned background scope.
 */
internal class DelayLoopGoogleSyncPeriodicTrigger(
    private val coordinatorProvider: () -> GoogleSyncCoordinator,
    scope: CoroutineScope,
) : GoogleSyncPeriodicTrigger {

    private val loop = DelayLoopSyncPeriodicTrigger(
        request = {
            // The gate is the interface method, so the loop and any future caller ask the
            // same question instead of the loop holding a private copy of the answer.
            if (isConfigured()) coordinatorProvider().syncNow()
        },
        scope = scope,
    )

    override fun start(interval: Duration) = loop.start(interval)

    override fun stop() = loop.stop()

    override suspend fun isConfigured(): Boolean = coordinatorProvider().isConfigured()
}
