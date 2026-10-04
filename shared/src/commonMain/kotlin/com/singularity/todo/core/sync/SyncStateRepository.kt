package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Per-scope sync state, as the rest of the sync core sees it.
 *
 * Reads never fail: a scope that has never synced has a row with `last_lsn = 0`, not a
 * missing record. The first-run state is the normal state, and a null here would push
 * a branch into every caller for a case that is not exceptional.
 */
interface SyncStateRepository {
    fun observe(scope: SyncScope): Flow<SyncState>
    suspend fun get(scope: SyncScope): SyncState
    suspend fun setLastLsn(scope: SyncScope, lsn: Long)
    suspend fun recordSuccessfulSync(scope: SyncScope, at: Long)
    suspend fun setAutoSyncEnabled(scope: SyncScope, enabled: Boolean)
    suspend fun setScheduledInterval(scope: SyncScope, interval: Duration)
    suspend fun setEnabledTriggers(scope: SyncScope, triggers: Set<SyncTrigger>)
    suspend fun clearAll()
}

/** [SyncStateEntity] with the conveniences callers actually want. */
data class SyncState(
    val lastLsn: Long = 0,
    val lastSuccessfulSyncAt: Long? = null,
    val deviceId: String? = null,
    val autoSyncEnabled: Boolean = true,
    val scheduledInterval: Duration = SyncStateEntity.DEFAULT_INTERVAL_MINUTES.minutes,
    val enabledTriggers: Set<SyncTrigger> = SyncTrigger.entries.toSet(),
) {
    companion object {
        fun from(entity: SyncStateEntity): SyncState = SyncState(
            lastLsn = entity.lastLsn,
            lastSuccessfulSyncAt = entity.lastSuccessfulSyncAt,
            deviceId = entity.deviceId,
            autoSyncEnabled = entity.autoSyncEnabled,
            scheduledInterval = entity.scheduledIntervalMinutes.minutes,
            enabledTriggers = entity.triggers,
        )
    }
}

/**
 * Room-backed [SyncStateRepository].
 *
 * ## One-time migration from the flat DataStore keys
 *
 * Sync state used to live in `user_settings` under `sync/last_lsn` and friends — one
 * value for the whole app, which is the bug this class exists to remove. The values are
 * adopted into the current scope on first use rather than dropped, because the cursor
 * is the one field whose loss is visible (the device re-downloads its history) and
 * because `deviceId` is the one field whose loss is *not* visible: regenerating it
 * makes the server treat the next sync as a brand-new client.
 *
 * Adoption is one-shot per scope and only when the row does not exist yet, so a scope
 * that has genuinely reset its cursor does not get the old value written back.
 */
internal class RoomSyncStateRepository(
    private val dao: SyncStateDao,
    private val legacyPrefs: SyncPrefs,
    private val idGenerator: IdGenerator,
) : SyncStateRepository {

    override fun observe(scope: SyncScope): Flow<SyncState> =
        dao.observe(scope.ownerId, scope.profileId).map { it?.let(SyncState::from) ?: SyncState() }

    override suspend fun get(scope: SyncScope): SyncState =
        SyncState.from(ensureRow(scope))

    override suspend fun setLastLsn(scope: SyncScope, lsn: Long) {
        ensureRow(scope)
        dao.setLastLsn(scope.ownerId, scope.profileId, lsn)
    }

    override suspend fun recordSuccessfulSync(scope: SyncScope, at: Long) {
        ensureRow(scope)
        dao.setLastSuccessfulSyncAt(scope.ownerId, scope.profileId, at)
    }

    override suspend fun setAutoSyncEnabled(scope: SyncScope, enabled: Boolean) {
        ensureRow(scope)
        dao.setAutoSyncEnabled(scope.ownerId, scope.profileId, enabled)
    }

    override suspend fun setScheduledInterval(scope: SyncScope, interval: Duration) {
        ensureRow(scope)
        dao.setScheduledIntervalMinutes(
            scope.ownerId,
            scope.profileId,
            interval.inWholeMinutes.toInt().coerceAtLeast(1),
        )
    }

    override suspend fun setEnabledTriggers(scope: SyncScope, triggers: Set<SyncTrigger>) {
        ensureRow(scope)
        dao.setEnabledTriggers(scope.ownerId, scope.profileId, SyncTrigger.toCsv(triggers))
    }

    override suspend fun clearAll() = dao.clearAll()

    private suspend fun ensureRow(scope: SyncScope): SyncStateEntity {
        val existing = dao.get(scope.ownerId, scope.profileId)
        if (existing != null) return existing

        val adopted = SyncStateEntity(
            ownerId = scope.ownerId,
            profileId = scope.profileId,
            lastLsn = legacyPrefs.lastLsn,
            lastSuccessfulSyncAt = legacyPrefs.lastSuccessfulSyncAt,
            // A device id is generated, not adopted — the old one lived in the session
            // store, and inventing a second source of truth for it would be worse than
            // generating a fresh one. Generating it here rather than at first use means
            // the row is complete the moment it exists, so nothing ever sends a sync
            // labelled with an empty device.
            deviceId = idGenerator.next(),
            autoSyncEnabled = legacyPrefs.autoSyncEnabled,
            scheduledIntervalMinutes = legacyPrefs.scheduledInterval
                .inWholeMinutes.toInt()
                .coerceAtLeast(1),
            enabledTriggers = SyncTrigger.toCsv(legacyPrefs.enabledTriggers),
        )
        // IGNORE, not REPLACE: two scopes can initialise concurrently, and whichever
        // loses the race must not overwrite the row that won with a different one.
        dao.insertIfAbsent(adopted)
        return dao.get(scope.ownerId, scope.profileId) ?: adopted
    }
}

/**
 * Supplies the scope sync currently operates on.
 *
 * A port rather than a direct dependency, because `core` must not reach into
 * `feature/profile` to ask which profile is active. The binding lives where both the
 * auth session and the profile are already known.
 */
interface SyncScopeProvider {
    val current: Flow<SyncScope?>
}
