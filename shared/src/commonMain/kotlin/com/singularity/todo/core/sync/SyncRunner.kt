package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Owns the lifecycle of periodic sync: start it when a session appears, stop it when
 * the session goes away, and route every cycle through [SyncCoordinator].
 *
 * This class is [internal] — constructed in the sync Koin module and hidden
 * from feature modules behind [SyncRepository].
 *
 * "When to fire" belongs to a [SyncPeriodicTrigger], which is a per-platform binding.
 * This class does not know which platform it is on, and neither does anything else
 * in the sync core: the previous version asked `scheduler is NoOpSyncScheduler` and
 * that question was answered wrongly on desktop for the entire life of the feature.
 */
internal class SyncRunner(
    private val engine: SyncEngine,
    private val coordinator: SyncCoordinator,
    private val periodicTrigger: SyncPeriodicTrigger,
    private val authRepository: AuthRepository,
    private val stateRepository: SyncStateRepository,
    private val scopeProvider: SyncScopeProvider,
    scope: AutoCloseableCoroutineScope,
    private val log: Logger = Logger.withTag("SyncRunner"),
) : AutoCloseable by scope {

    // Expose scope for coroutine builders that need an explicit CoroutineScope reference.
    private val scopeRef: CoroutineScope = scope

    val status = engine.status
    val lastPush = engine.lastPush
    val lastPull = engine.lastPull

    private var scheduledJob: Job? = null

    init {
        // React to scope changes *and* to the settings themselves.
        //
        // Auto-sync settings are per scope, so watching the session alone means a
        // profile switch keeps running the previous profile's interval — and a
        // profile that has auto-sync turned off inherits one that has it on. The
        // scope is the sum of the session and the active profile, so watching it
        // covers both.
        //
        // But the scope alone is not enough, and the reason is a property of `StateFlow`
        // rather than of this code: a `StateFlow` does not emit when it is set to the
        // value it already holds. Changing the sync interval therefore produced no
        // emission, the runner never re-read the settings, and the one setting on that
        // screen whose entire purpose is to be changed did nothing until the profile was
        // switched or the app restarted. `autoSyncEnabled` failed the same way in both
        // directions.
        //
        // `collectLatest` rather than `collect` is what keeps the two collectors from
        // overlapping: a scope change cancels the settings collection that belongs to
        // the previous scope, rather than leaving it live beside the new one.
        scope.launch {
            scopeProvider.current.collectLatest { active ->
                if (active == null) {
                    stopScheduledSync()
                    return@collectLatest
                }
                // Narrowed to the two fields scheduling actually depends on, and then
                // de-duplicated. `SyncState` also carries `lastLsn`, `lastSuccessfulSyncAt`
                // and `deviceId`, which change on every cycle — observing it whole would
                // cancel and restart the periodic trigger after each sync, so the trigger
                // would be rearmed constantly and an interval could pass without one ever
                // completing. Only a real scheduling change may restart it.
                stateRepository.observe(active)
                    .map { it.autoSyncEnabled to it.scheduledInterval }
                    .distinctUntilChanged()
                    .collect { (enabled, interval) ->
                        if (enabled) {
                            startScheduledSync(interval)
                        } else {
                            stopScheduledSync()
                        }
                    }
            }
        }
    }

    /**
     * Starts periodic [SyncCoordinator.request] at the given [interval].
     *
     * The periodic trigger is a platform concern, so it is delegated to
     * [periodicTrigger] rather than sniffed from the scheduler's type. A type test
     * is the wrong tool twice over: it makes the *bindings* decide behaviour, and
     * it is wrong in a way that fails silently.
     *
     * The previous version asked `scheduler is NoOpSyncScheduler` and ran the delay
     * loop on that answer. The JVM module binds [JvmSyncScheduler], not
     * [NoOpSyncScheduler] — see `PlatformModule.jvm.kt` — so the test was always
     * false on desktop, the loop was never created, and `startScheduledSync` reduced
     * to a log line inside a no-op. Desktop auto-sync had never run. A comment in
     * this file asserted the opposite for as long as that bug existed.
     */
    fun startScheduledSync(interval: Duration) {
        scheduledJob?.cancel()
        scheduledJob = scopeRef.launch {
            periodicTrigger.start(interval)
        }
        log.d { "Scheduled sync started (interval=$interval)" }
    }

    /**
     * Stops the periodic sync job.
     */
    fun stopScheduledSync() {
        scheduledJob?.cancel()
        scheduledJob = null
        periodicTrigger.stop()
        log.d { "Scheduled sync stopped" }
    }

    /**
     * Runs one push + pull cycle if the user is signed in.
     *
     * Goes through [SyncCoordinator], not straight to the engine. Calling the engine
     * directly — as this did — was a second, unguarded path to the cycle: the
     * desktop delay loop could run a cycle concurrently with one started from the
     * settings screen, and the repository's coalescing guard could not see it.
     */
    suspend fun syncOnce() {
        if (authRepository.currentSession.value !is Session.SignedIn) {
            log.d { "syncOnce skipped: not signed in" }
            return
        }
        coordinator.request()
    }
}
