package com.singularity.todo.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * In-memory [SyncPrefs] for tests. Production uses [DataStoreSyncPrefs].
 *
 * ## Why this lives in `jvmTest` and not in `commonMain`
 *
 * It was declared in `commonMain` alongside [SyncPrefs] and [DataStoreSyncPrefs], but every
 * reference to it comes from `jvmTest` — six call sites in `SyncStateMigrationTest`, plus
 * its own declaration and the KDoc that names it. A test double that no production code
 * can reach is not a production class; leaving it in `commonMain` shipped it in every
 * release for no benefit.
 *
 * That is the same debt `deferred-backlog.md:test-doubles-in-commonmain-source` records,
 * and the same shape as `MapFileSystem`, `FakeSecureStorage` and `FakeDraftStore` — the
 * other three doubles in this repository, which are already baselined for it. This one is
 * moved rather than baselined, because the only thing a baseline entry buys is a tracked
 * exemption, and a class that is in the right source set needs no exemption.
 *
 * Moving it rather than deleting it: the six call sites are load-bearing. They exercise the
 * v35 → v36 and v36 → v37 migration paths against a pre-migration preference set, and
 * replacing them with a mock would test the mock instead of the migration.
 */
class InMemorySyncPrefs(private val clock: Clock) : SyncPrefs {
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

    override suspend fun setAutoSyncEnabled(value: Boolean) {
        _autoSyncEnabled.value = value
    }

    override suspend fun setEnabledTriggers(triggers: Set<SyncTrigger>) {
        _enabledTriggers.value = triggers
    }

    override suspend fun setScheduledInterval(interval: Duration) {
        _scheduledInterval.value = interval
    }

    override suspend fun recordSuccessfulSync() {
        _lastSuccessfulSyncAt.value = clock.now().toEpochMilliseconds()
    }

    override suspend fun setLastLsn(lsn: Long) {
        _lastLsn.value = lsn
    }
}
