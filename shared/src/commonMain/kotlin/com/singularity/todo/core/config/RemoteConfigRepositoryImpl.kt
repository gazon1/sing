@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.core.config

import co.touchlab.kermit.Logger
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.SyncApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
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
 * @param syncApi Currently a stub in MR-2. Full implementation deferred to sync backend work.
 */
internal class RemoteConfigRepositoryImpl(
    private val cacheDao: RemoteConfigCacheDao,
    private val syncApi: SyncApiClient,
    private val log: Logger = Logger.withTag("RemoteConfigPort"),
) : RemoteConfigPort {

    private val _snapshot: MutableStateFlow<RemoteConfigSnapshot> =
        MutableStateFlow(RemoteConfigSnapshot.defaults())

    override fun observe(): StateFlow<RemoteConfigSnapshot> = _snapshot.asStateFlow()

    override suspend fun snapshot(): RemoteConfigSnapshot {
        val cached = cacheDao.getDefault()
        val result = if (cached != null) {
            deserializeOrDefaults(cached.snapshotJson)
        } else {
            RemoteConfigSnapshot.defaults()
        }
        _snapshot.value = result
        return result
    }

    override suspend fun refresh(): Result<RemoteConfigSnapshot> {
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
                    fetchedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                )
                cacheDao.upsert(entity)
                _snapshot.value = validated
                log.d { "Remote config refreshed, schemaVersion=${validated.schemaVersion}" }
                Result.success(validated)
            }
        } catch (e: Throwable) {
            log.w(e) { "Remote config refresh failed, using cache" }
            snapshot() // ensure _snapshot is updated to cached/defaults on network error
            Result.failure(e)
        }
    }

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
