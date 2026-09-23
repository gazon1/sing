package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fans out sync triggers from app lifecycle / network events to [SyncRepository].
 *
 * Orgzly pattern: one entry point ([trigger]) that respects global enable/disable
 * and per-trigger toggles stored in [SyncPrefs].
 */
class AutoSync(
    private val prefs: SyncPrefs,
    private val repository: SyncRepository,
    private val log: Logger = Logger.withTag("AutoSync"),
) {
    /**
     * Fires a sync if the trigger is enabled in preferences.
     * Callers pass their own [CoroutineScope] — typically [viewModelScope][androidx.lifecycle.viewModelScope]
     * for UI components or [createBackgroundScope] for repositories.
     */
    fun trigger(t: SyncTrigger, scope: CoroutineScope) {
        if (!prefs.autoSyncEnabled) {
            log.d { "AutoSync disabled, ignoring trigger $t" }
            return
        }
        if (t !in prefs.enabledTriggers) {
            log.d { "Trigger $t not enabled, ignoring" }
            return
        }
        scope.launch {
            log.d { "AutoSync triggering sync for $t" }
            repository.syncOnce()
        }
    }
}
