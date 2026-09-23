package com.singularity.todo.core.config

import kotlinx.coroutines.flow.StateFlow

/**
 * Port for remote runtime configuration — feature flags, kill switches,
 * minimum supported version, and maintenance banners.
 *
 * Fetched from the sync backend (or a dedicated config endpoint), cached in Room,
 * and observed as a [StateFlow]. The app subscribes to this flow on startup
 * to react to changes without a full restart.
 *
 * ## Cache strategy
 * - [snapshot] returns the current cached value immediately (no network call).
 * - [refresh] fetches from the network and updates the cache.
 * - If the network is unavailable, [snapshot] returns the last cached values
 *   (last-known-good pattern) or [RemoteConfigSnapshot.defaults].
 * - TTL is 6 hours; a background job refreshes automatically.
 *
 * ## Schema versioning
 * If the server returns a schema version newer than
 * [RemoteConfigSnapshot.CURRENT_SCHEMA_VERSION], the payload is rejected
 * and the cached value (or defaults) is used instead.
 *
 * ## Thread safety
 * Implementations must be safe for concurrent access from multiple coroutines.
 */
interface RemoteConfigPort {

    /**
     * Returns the current cached [RemoteConfigSnapshot] without a network call.
     * Emits [RemoteConfigSnapshot.defaults] if no cache exists.
     */
    suspend fun snapshot(): RemoteConfigSnapshot

    /**
     * Forces a network fetch and updates the cache.
     * Returns [Result.success] with the new snapshot on success,
     * [Result.failure] on network or schema error (falls back to cache).
     */
    suspend fun refresh(): Result<RemoteConfigSnapshot>

    /**
     * An observable [StateFlow] that emits after each successful [refresh].
     * The initial value is the cached snapshot (or defaults).
     *
     * Subscribers are notified of changes to [RemoteConfigSnapshot.minSupportedVersion]
     * (version gate), [RemoteConfigSnapshot.maintenanceBanner], and any flag changes.
     */
    fun observe(): StateFlow<RemoteConfigSnapshot>
}
