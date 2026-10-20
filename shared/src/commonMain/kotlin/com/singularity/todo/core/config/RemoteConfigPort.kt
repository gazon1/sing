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
 * ## Bounded reads
 *
 * [snapshot] and [refresh] bound the I/O they perform by [READ_TIMEOUT_MS]. A read that
 * does not come back in time **ends on its own terms** instead of hanging the caller:
 * the port gives up on it and answers with the last-known-good snapshot ([snapshot]) or
 * with a failure carrying [READ_TIMEOUT_CODE] ([refresh]).
 *
 * The bound belongs to the port, not to the caller. A deadline in a caller can only end
 * a *cooperative* read — one that honours cancellation — so a read that blocks in a
 * driver call would still hold that caller past its deadline. The port runs the read
 * somewhere it can walk away from, which is what makes "returns within a bounded time
 * and never hangs" a property of the contract rather than a hope.
 *
 * A caller may still impose its own deadline on top of this one — the version gate
 * does, because the deadline it shows the user is the guarantee the UI sees. The two
 * are expected to be the same number: [READ_TIMEOUT_MS] is the one place it is written
 * down, so the port's bound and the caller's deadline cannot drift apart.
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
     *
     * The read is bounded by [READ_TIMEOUT_MS]: a cache that does not answer in time
     * does not hang the caller — the last-known-good snapshot is returned instead (the
     * defaults until the first successful read).
     */
    suspend fun snapshot(): RemoteConfigSnapshot

    /**
     * Forces a network fetch and updates the cache.
     * Returns [Result.success] with the new snapshot on success,
     * [Result.failure] on network or schema error (falls back to cache).
     *
     * The fetch is bounded by [READ_TIMEOUT_MS]. A fetch that misses the deadline fails
     * with an [com.singularity.todo.core.error.AppError] whose code is
     * [READ_TIMEOUT_CODE] — a failure, because this method's contract is a [Result], and
     * a caller that handles failures already handles this one.
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

    companion object {
        /**
         * Upper bound on one remote-config read, in milliseconds.
         *
         * Long enough for the slow case that is still a success — a Room database created
         * on first launch, a cold multi-instance lock — and short enough that a user does
         * not sit in front of a splash wondering whether the app is broken.
         *
         * This is the one number: the port bounds its own read by it, and a caller that
         * imposes a deadline of its own is expected to impose this one.
         */
        const val READ_TIMEOUT_MS = 5_000L

        /**
         * The machine-shaped code a read that missed [READ_TIMEOUT_MS] is reported under.
         *
         * Its own code, so a timeout is a row of its own in a report rather than one line
         * inside the general "the config could not be read" bucket: a slow disk and a broken
         * config server are fixed in opposite places.
         */
        const val READ_TIMEOUT_CODE = "config.read_timeout"

        /**
         * How long a cached configuration stays fresh, in milliseconds.
         *
         * This is the one number: the cache implementation expires on it, and a caller
         * that wants to say "the configuration I am acting on is stale" compares against
         * it rather than inventing a second deadline. A caller that cannot see the
         * cache at all must say so separately — an old configuration and no
         * configuration are different facts, and a tool that reports both as "the
         * defaults" cannot tell an operator which one they are in.
         */
        const val CACHE_TTL_MILLIS = 6 * 60 * 60 * 1_000L
    }
}
