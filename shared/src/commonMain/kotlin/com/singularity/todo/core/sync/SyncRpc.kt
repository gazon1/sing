package com.singularity.todo.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * A named remote procedure call, returning its raw JSON response body.
 *
 * ## Why this is a port and not the vendor client
 *
 * The transport needs exactly one capability from the Supabase SDK: call a
 * Postgres function and hand back what it returned. Expressing that as a port
 * with one method buys three things that are otherwise unavailable.
 *
 * **The wire format becomes testable.** Parsing a batch response, and reading a
 * log sequence number that does not survive a round trip through a `Double`, are
 * the two places this transport can silently corrupt a user's data. Both are
 * properties of the JSON, not of the HTTP client, so both are testable against a
 * canned body with no network and no engine.
 *
 * **The vendor SDK cannot leak inward.** A `SupabaseClient` handed to a
 * ViewModel or a repository is a session, a retry policy, a cache and a logger
 * that all become reachable; the boundary erodes one convenient import at a
 * time. `VendorSdkConfinementTest` holds the line, and it can only mean
 * something if the surface it guards is small enough to be stated.
 *
 * **The identity stays where it belongs.** The caller does not pass a user id,
 * does not set an `Authorization` header and does not construct a client. The
 * session the vendor client already holds is the identity, which is the same
 * property `SyncTransportIdentityTest` enforces on the interface above this one.
 *
 * Implementations return the response body verbatim and let the failure surface
 * as a thrown exception; deciding what a failure *means* belongs to
 * [SupabaseSyncApiClient], which owns the error vocabulary.
 */
interface SyncRpc {
    /**
     * Calls [function] with [params] and returns the raw JSON response body.
     *
     * Throws whatever the underlying transport throws. Callers that need a typed
     * failure translate at the boundary — see [SupabaseSyncApiClient].
     */
    suspend fun call(function: String, params: JsonObject): String
}
