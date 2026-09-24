package com.singularity.todo.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.di.koinBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * DataStore-backed implementation of [SyncPrefs].
 *
 * Uses the `user_settings` [DataStore<Preferences>], prefixed under the `sync/` namespace.
 * All state is cached in [MutableStateFlow] so callers always read the latest value synchronously.
 * Changes are written through to DataStore on every mutating call.
 *
 * Construction is blocking (reads DataStore once via [koinBridge]) — safe because it runs
 * at module-init time, not on a hot path.
 *
 * Mutating setters are also synchronous via [koinBridge] — one-shot blocking writes
 * keep the interface non-suspend at the cost of blocking the calling thread (UI thread).
 * This is acceptable for settings changes which are infrequent and user-initiated.
 */
class DataStoreSyncPrefs(private val dataStore: DataStore<Preferences>) : SyncPrefs {

    private object Keys {
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("sync/auto_sync_enabled")
        val ENABLED_TRIGGERS = stringPreferencesKey("sync/enabled_triggers")
        val SCHEDULED_INTERVAL = longPreferencesKey("sync/scheduled_interval_minutes")
        val LAST_SUCCESSFUL_SYNC_AT = longPreferencesKey("sync/last_successful_sync_at")
        val LAST_LSN = longPreferencesKey("sync/last_lsn")

        fun parseTriggers(raw: String): Set<SyncTrigger> = if (raw.isBlank()) {
            SyncTrigger.entries.toSet()
        } else {
            raw.split(',').mapNotNull { name ->
                SyncTrigger.entries.find { it.name == name }
            }.toSet()
        }

        fun serializeTriggers(triggers: Set<SyncTrigger>): String = triggers.joinToString(",") { it.name }
    }

    private val _autoSyncEnabled = MutableStateFlow(false)
    private val _enabledTriggers = MutableStateFlow(SyncTrigger.entries.toSet())
    private val _scheduledInterval = MutableStateFlow(30.minutes)
    private val _lastSuccessfulSyncAt = MutableStateFlow<Long?>(null)
    private val _lastLsn = MutableStateFlow(0L)

    override val autoSyncEnabled: Boolean get() = _autoSyncEnabled.value
    override val enabledTriggers: Set<SyncTrigger> get() = _enabledTriggers.value
    override val scheduledInterval: Duration get() = _scheduledInterval.value
    override val lastSuccessfulSyncAt: Long? get() = _lastSuccessfulSyncAt.value
    override val lastLsn: Long get() = _lastLsn.value

    init {
        // Seed MutableStateFlows from DataStore at construction.
        // Uses koinBridge (runBlocking) so this is a one-shot synchronous init — not on a hot path.
        koinBridge {
            val prefs = dataStore.data.first()
            _autoSyncEnabled.value = prefs[Keys.AUTO_SYNC_ENABLED] ?: false
            _enabledTriggers.value = Keys.parseTriggers(prefs[Keys.ENABLED_TRIGGERS] ?: "")
            _scheduledInterval.value = (prefs[Keys.SCHEDULED_INTERVAL] ?: 30).minutes
            _lastSuccessfulSyncAt.value = prefs[Keys.LAST_SUCCESSFUL_SYNC_AT]
            _lastLsn.value = prefs[Keys.LAST_LSN] ?: 0L
        }
    }

    override fun setAutoSyncEnabled(value: Boolean) {
        _autoSyncEnabled.value = value
        koinBridge { dataStore.edit { it[Keys.AUTO_SYNC_ENABLED] = value } }
    }

    override fun setEnabledTriggers(triggers: Set<SyncTrigger>) {
        _enabledTriggers.value = triggers
        koinBridge { dataStore.edit { it[Keys.ENABLED_TRIGGERS] = Keys.serializeTriggers(triggers) } }
    }

    override fun setScheduledInterval(interval: Duration) {
        _scheduledInterval.value = interval
        koinBridge { dataStore.edit { it[Keys.SCHEDULED_INTERVAL] = interval.inWholeMinutes } }
    }

    override suspend fun recordSuccessfulSync() {
        val now = System.currentTimeMillis()
        _lastSuccessfulSyncAt.value = now
        dataStore.edit { it[Keys.LAST_SUCCESSFUL_SYNC_AT] = now }
    }

    override suspend fun setLastLsn(lsn: Long) {
        _lastLsn.value = lsn
        dataStore.edit { it[Keys.LAST_LSN] = lsn }
    }
}

/**
 * In-memory stub of [SyncPrefs] for tests.
 * Replaced by [DataStoreSyncPrefs] in production.
 */
class InMemorySyncPrefs : SyncPrefs {
    private val _autoSyncEnabled = MutableStateFlow(false)
    private val _enabledTriggers = MutableStateFlow(SyncTrigger.entries.toSet())
    private val _scheduledInterval = MutableStateFlow(30.minutes)
    private val _lastSuccessfulSyncAt = MutableStateFlow<Long?>(null)
    private val _lastLsn = MutableStateFlow(0L)

    override val autoSyncEnabled: Boolean get() = _autoSyncEnabled.value
    override val enabledTriggers: Set<SyncTrigger> get() = _enabledTriggers.value
    override val scheduledInterval: Duration get() = _scheduledInterval.value
    override val lastSuccessfulSyncAt: Long? get() = _lastSuccessfulSyncAt.value
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
    val lastSuccessfulSyncAt: Long? // epoch millis
    val lastLsn: Long // last server log sequence number

    fun setAutoSyncEnabled(value: Boolean)
    fun setEnabledTriggers(triggers: Set<SyncTrigger>)
    fun setScheduledInterval(interval: Duration)
    suspend fun recordSuccessfulSync()
    suspend fun setLastLsn(lsn: Long)
}
