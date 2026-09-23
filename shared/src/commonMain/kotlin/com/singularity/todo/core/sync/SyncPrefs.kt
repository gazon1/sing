package com.singularity.todo.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * In-memory implementation of [SyncPrefs] for Tier 1.
 * Replaced by DataStore-backed [DataStoreSyncPrefs] in Tier 2.
 */
class InMemorySyncPrefs : SyncPrefs {
    private val _autoSyncEnabled = MutableStateFlow(false)
    override val autoSyncEnabled: Boolean get() = _autoSyncEnabled.value

    private val _enabledTriggers = MutableStateFlow(SyncTrigger.entries.toSet())
    override val enabledTriggers: Set<SyncTrigger> get() = _enabledTriggers.value

    private val _scheduledInterval = MutableStateFlow(30.minutes)
    override val scheduledInterval: Duration get() = _scheduledInterval.value

    private val _lastSuccessfulSyncAt = MutableStateFlow< Long?>(null)
    override val lastSuccessfulSyncAt: Long? get() = _lastSuccessfulSyncAt.value

    private val _lastLsn = MutableStateFlow(0L)
    override val lastLsn: Long get() = _lastLsn.value

    override fun setAutoSyncEnabled(value: Boolean) {
        _autoSyncEnabled.value = value
    }

    override fun setEnabledTriggers(triggers: Set<SyncTrigger>) {
        _enabledTriggers.value = triggers
    }

    override fun setScheduledInterval(interval: Duration) {
        _scheduledInterval.value = interval
    }

    override suspend fun recordSuccessfulSync() {
        _lastSuccessfulSyncAt.value = System.currentTimeMillis()
    }

    override suspend fun setLastLsn(lsn: Long) {
        _lastLsn.value = lsn
    }
}

/**
 * Preferences for sync behaviour.
 *
 * [SyncPrefs] is backed by DataStore in production ([DataStoreSyncPrefs])
 * and by [InMemorySyncPrefs] in tests.
 */
interface SyncPrefs {
    val autoSyncEnabled: Boolean
    val enabledTriggers: Set<SyncTrigger>
    val scheduledInterval: Duration
    val lastSuccessfulSyncAt: Long?   // epoch millis
    val lastLsn: Long                 // last server log sequence number

    fun setAutoSyncEnabled(value: Boolean)
    fun setEnabledTriggers(triggers: Set<SyncTrigger>)
    fun setScheduledInterval(interval: Duration)
    suspend fun recordSuccessfulSync()
    suspend fun setLastLsn(lsn: Long)
}
