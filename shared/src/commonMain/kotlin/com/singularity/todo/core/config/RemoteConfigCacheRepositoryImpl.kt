package com.singularity.todo.core.config

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.coroutines.loggingBackgroundFailureHandler
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.SyncApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * Room + network implementation of [RemoteConfigPort].
 *
 * ## Cache strategy
 * - [snapshot] returns the cached [RemoteConfigCacheEntity.snapshotJson] deserialized
 *   via [StableJson] immediately, without any network call.
 * - [refresh] fetches from [SyncApiClient.getRemoteConfig] (stub in MR-2),
 *   validates the schema version, and upserts to [RemoteConfigCacheDao].
 * - If the network is unavailable, [refresh] returns [Result.failure] and
 *   the cached value (or [RemoteConfigSnapshot.defaults]) remains in use.
 * - TTL is [TTL_MILLIS]; after TTL a background job triggers [refresh] automatically.
 *
 * ## Schema versioning
 * [RemoteConfigSnapshot.validate] returns null for unknown/too-new schema.
 * In that case the incoming payload is rejected and the cached value (or defaults) is used.
 *
 * ## The bound lives here, not in the caller
 *
 * Both public reads are bounded by [RemoteConfigPort.READ_TIMEOUT_MS], and the bound is
 * enforced *around the I/O*, not around the caller's coroutine — see [readWithinDeadline].
 * The read runs in a scope of its own, so a read that ignores cancellation (a blocking
 * driver call) cannot hold the caller past the deadline: the caller waits on a promise of
 * the result, and a promise can be given up on whether or not the work behind it finishes.
 *
 * A deadline in a caller could not do this. The version gate has one anyway — it is the
 * guarantee the UI sees — but it is the outer of two layers, not the only one.
 *
 * @param syncApi Currently a stub in MR-2. Full implementation deferred to sync backend work.
 * @param readTimeoutMs Upper bound on one read, in milliseconds. Defaults to the port's own
 *   [RemoteConfigPort.READ_TIMEOUT_MS], so the bound this implementation enforces and the
 *   deadline a caller enforces are the same number.
 * @param readScope Where the bounded read runs. It is a scope of its own on purpose: a read
 *   that misses its deadline is left running there, and the caller stops waiting for it.
 */
internal class RemoteConfigCacheRepositoryImpl(
    private val cacheDao: RemoteConfigCacheDao,
    private val syncApi: SyncApiClient,
    private val log: Logger = Logger.withTag("RemoteConfigPort"),
    // When the config was fetched is a property of the fetch, and a test asserting
    // it needs to be able to choose it. Read from the system clock it was not: the
    // class had no clock at all, so `fetchedAtEpochMillis` was whatever the host's
    // wall clock said and nothing could check it (#91).
    private val clock: Clock,
    private val readTimeoutMs: Long = RemoteConfigPort.READ_TIMEOUT_MS,
    private val readScope: CoroutineScope = createBackgroundScope(loggingBackgroundFailureHandler()),
) : RemoteConfigPort {

    private val _snapshot: MutableStateFlow<RemoteConfigSnapshot> =
        MutableStateFlow(RemoteConfigSnapshot.defaults())

    override fun observe(): StateFlow<RemoteConfigSnapshot> = _snapshot.asStateFlow()

    override suspend fun snapshot(): RemoteConfigSnapshot {
        val read = readWithinDeadline { readCachedSnapshot() }
        if (read == null) {
            // The read is still running; it may yet succeed and `observe()` will publish
            // what it produced. Only the waiting ended.
            log.w { "Remote config read did not finish within $readTimeoutMs ms, serving the last known good snapshot" }
            return lastKnownGood()
        }
        return read
    }

    override suspend fun refresh(): Result<RemoteConfigSnapshot> {
        val read = readWithinDeadline { refreshInternal() }
        if (read == null) {
            log.w { "Remote config refresh did not finish within $readTimeoutMs ms" }
            // A failure, not a silent success: this method's contract is a Result, and a
            // caller that handles a failed refresh already handles this one. The code says
            // "timed out" so it is not filed next to "the config server is down".
            return Result.failure(
                AppError.Network(
                    message = "Remote config refresh timed out after $readTimeoutMs ms",
                    code = RemoteConfigPort.READ_TIMEOUT_CODE,
                ),
            )
        }
        return read
    }

    /**
     * Reads the cached row and publishes it as the current snapshot.
     */
    private suspend fun readCachedSnapshot(): RemoteConfigSnapshot {
        val cached = cacheDao.getDefault()
        val result = if (cached != null) {
            deserializeOrDefaults(cached.snapshotJson)
        } else {
            RemoteConfigSnapshot.defaults()
        }
        _snapshot.value = result
        return result
    }

    /**
     * The fetch, the validation and the cache write — everything a refresh does, minus
     * the deadline, which [refresh] applies around it.
     */
    private suspend fun refreshInternal(): Result<RemoteConfigSnapshot> {
        return try {
            val json = syncApi.getRemoteConfig()
            if (json == null) {
                val current = snapshot()
                Result.success(current)
            } else {
                val validated = RemoteConfigSnapshot.validate(json)
                if (validated == null) {
                    log.w { "Remote config schema validation failed, using cache" }
                    val cached = snapshot()
                    return Result.failure(Exception("Remote config schema validation failed"))
                }
                val entity = RemoteConfigCacheEntity(
                    snapshotJson = StableJson.encodeToString(RemoteConfigSnapshot.serializer(), validated),
                    fetchedAtEpochMillis = clock.now().toEpochMilliseconds(),
                )
                cacheDao.upsert(entity)
                _snapshot.value = validated
                log.d { "Remote config refreshed, schemaVersion=${validated.schemaVersion}" }
                Result.success(validated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "Remote config refresh failed, using cache" }
            snapshot() // ensure _snapshot is updated to cached/defaults on network error
            Result.failure(e)
        }
    }

    /**
     * Runs [read] under a deadline that the read cannot outlast.
     *
     * The read runs in [readScope] — a scope of its own, not the caller's — and the
     * caller waits for the result with [withTimeoutOrNull]. That separation is the whole
     * point: a deadline can only end a *cooperative* read, so a read that ignores
     * cancellation (a blocking driver call) would still hold the caller past the deadline
     * if it ran in the caller's own coroutine. Here the caller waits on a promise of the
     * result, and a promise can be given up on whether or not the work behind it ever
     * finishes.
     *
     * A read that misses the deadline is left running: it may still succeed, and
     * [observe] publishes whatever it produced. Only the waiting ends.
     *
     * @return the value [read] produced, or null if the deadline was missed.
     */
    private suspend fun <T : Any> readWithinDeadline(read: suspend () -> T): T? {
        val pending = readScope.async { read() }
        val completed = withTimeoutOrNull(readTimeoutMs) { pending.await() }
        if (completed == null) {
            // The caller stopped waiting; the read did not. If it fails now, nobody is
            // left to handle the failure, so it is logged rather than dropped.
            pending.invokeOnCompletion { cause ->
                if (cause != null && cause !is CancellationException) {
                    log.w(cause) { "Remote config read failed after the caller had given up on it" }
                }
            }
        }
        return completed
    }

    /**
     * The last snapshot this port served — the defaults until the first successful read.
     *
     * The port's documented fallback is the last-known-good value, and a read that missed
     * its deadline has not produced a newer one: it has produced *no* value. Serving the
     * older one is the same answer the port gives when the network is unavailable, and it
     * is the only answer that does not throw away a config the app has already decided to
     * act on (a minimum version, a kill switch).
     */
    private fun lastKnownGood(): RemoteConfigSnapshot = _snapshot.value

    private fun deserializeOrDefaults(json: String): RemoteConfigSnapshot = try {
        val element = StableJson.parseToJsonElement(json)
        val jsonObj = element as? JsonObject
        if (jsonObj != null) {
            RemoteConfigSnapshot.validate(jsonObj) ?: RemoteConfigSnapshot.defaults()
        } else {
            RemoteConfigSnapshot.defaults()
        }
    } catch (e: Throwable) {
        log.w(e) { "Failed to deserialize cached RemoteConfigSnapshot, using defaults" }
        RemoteConfigSnapshot.defaults()
    }

    companion object {
        /** TTL for the remote config cache. 6 hours in milliseconds. */
        const val TTL_MILLIS = 6 * 60 * 60 * 1_000L
    }
}
