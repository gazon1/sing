package com.singularity.todo.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * DataStore-backed implementation of [SyncPrefs].
 *
 * Uses the `user_settings` [DataStore<Preferences>], prefixed under the `sync/` namespace.
 * All state is cached in [MutableStateFlow] so callers always read the latest value synchronously.
 * Changes are written through to DataStore on every mutating call.
 *
 * Construction is non-blocking — the initial DataStore read runs asynchronously via [scope].
 * All mutating operations are proper `suspend` functions (no [runBlocking]).
 */
class DataStoreSyncPrefs(
    private val dataStore: DataStore<Preferences>,
    private val scope: AutoCloseableCoroutineScope,
    /**
     * Wall-clock time, injected like everywhere else. The "last synced" stamp is the
     * only thing this reads, and reading it from the system directly meant a test
     * could not assert what the screen shows without tolerating a real clock.
     */
    private val clock: Clock,
    private val log: co.touchlab.kermit.Logger = co.touchlab.kermit.Logger.withTag("DataStoreSyncPrefs"),
) : SyncPrefs {

    private object Keys {
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("sync/auto_sync_enabled")
        val ENABLED_TRIGGERS = stringPreferencesKey("sync/enabled_triggers")
        val SCHEDULED_INTERVAL = longPreferencesKey("sync/scheduled_interval_minutes")
        val LAST_SUCCESSFUL_SYNC_AT = longPreferencesKey("sync/last_successful_sync_at")
        val LAST_LSN = longPreferencesKey("sync/last_lsn")

        // Delegates rather than re-implementing: this class used to carry its own copy of
        // the CSV codec, and the two had drifted — both treated "" as "all triggers", which
        // meant an explicitly empty set could not survive a round trip. [SyncTrigger] owns
        // the format and knows the sentinel for the empty set.
        fun parseTriggers(raw: String?): Set<SyncTrigger> = SyncTrigger.parseCsv(raw)

        fun serializeTriggers(triggers: Set<SyncTrigger>): String = SyncTrigger.toCsv(triggers)
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
        scope.launch {
            runCatching {
                val prefs = dataStore.data.first()
                _autoSyncEnabled.value = prefs[Keys.AUTO_SYNC_ENABLED] ?: false
                // No "?: \"\"" here: an absent key already means "all triggers" (parseCsv
                // treats null that way), and substituting "" would be indistinguishable
                // from a stored value while hiding the difference from the reader.
                _enabledTriggers.value = Keys.parseTriggers(prefs[Keys.ENABLED_TRIGGERS])
                _scheduledInterval.value = (prefs[Keys.SCHEDULED_INTERVAL] ?: 30).minutes
                _lastSuccessfulSyncAt.value = prefs[Keys.LAST_SUCCESSFUL_SYNC_AT]
                _lastLsn.value = prefs[Keys.LAST_LSN] ?: 0L
            }.onFailure { e ->
                // #262: a corrupted DataStore file on first read silently leaves sync running
                // with defaults. Log the error so operators can see it happened.
                log.w(e) { "SyncPrefs init read failed; using defaults. ${e.message}" }
            }
        }
    }

    override suspend fun setAutoSyncEnabled(value: Boolean) {
        _autoSyncEnabled.value = value
        dataStore.edit { it[Keys.AUTO_SYNC_ENABLED] = value }
    }

    override suspend fun setEnabledTriggers(triggers: Set<SyncTrigger>) {
        _enabledTriggers.value = triggers
        dataStore.edit { it[Keys.ENABLED_TRIGGERS] = Keys.serializeTriggers(triggers) }
    }

    override suspend fun setScheduledInterval(interval: Duration) {
        _scheduledInterval.value = interval
        dataStore.edit { it[Keys.SCHEDULED_INTERVAL] = interval.inWholeMinutes }
    }

    override suspend fun recordSuccessfulSync() {
        val nowMillis = clock.now().toEpochMilliseconds()
        _lastSuccessfulSyncAt.value = nowMillis
        dataStore.edit { it[Keys.LAST_SUCCESSFUL_SYNC_AT] = nowMillis }
    }

    override suspend fun setLastLsn(lsn: Long) {
        _lastLsn.value = lsn
        dataStore.edit { it[Keys.LAST_LSN] = lsn }
    }
}

/**
 * The flat, app-wide sync preferences that [SyncStateRepository] replaced.
 *
 * ## Read-only in practice — and that is the point
 *
 * Every caller has moved to [SyncStateRepository], which keys the same values by
 * `(owner, profile)`. The one production read left is
 * [RoomSyncStateRepository]'s one-shot adoption of these values, so that upgrading
 * does not cost the user a full re-download.
 *
 * The setters are therefore **not** the way to change sync state any more, and a
 * new call to one would reintroduce the bug this class is being retired from: a
 * single global value that every profile shares. They remain because the adoption
 * path reads through the same interface, and deleting them would mean duplicating
 * the read shape in a second type.
 *
 * Delete this file once the adoption window has passed — the criteria are that no
 * supported upgrade path can still hold values here.
 *
 * [SyncPrefs] is backed by DataStore in production ([DataStoreSyncPrefs]) and by
 * `InMemorySyncPrefs` in tests — the latter lives in `jvmTest`, next to its only callers,
 * so it is no longer a class shipped in every release.
 */
interface SyncPrefs {
    val autoSyncEnabled: Boolean
    val enabledTriggers: Set<SyncTrigger>
    val scheduledInterval: Duration
    val lastSuccessfulSyncAt: Long? // epoch millis
    val lastLsn: Long // last server log sequence number

    suspend fun setAutoSyncEnabled(value: Boolean)
    suspend fun setEnabledTriggers(triggers: Set<SyncTrigger>)
    suspend fun setScheduledInterval(interval: Duration)
    suspend fun recordSuccessfulSync()
    suspend fun setLastLsn(lsn: Long)
}
