package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.transport.SupabaseTransportFailure
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * [SyncRpc] over the Supabase PostgREST client.
 *
 * ## The identity is not passed in
 *
 * [Postgrest] is obtained from a client the provider already authenticated. A
 * constructor taking a `SupabaseClient` would invite callers to build one — and
 * one built with only an anon key is a caller with no identity at all, which the
 * server answers by refusing every request. Taking the *provider* instead means
 * there is exactly one client, and it is the authenticated one.
 *
 * ## What the failures say
 *
 * The vendor's failures are read here, where the vendor's types are: the status
 * code, PostgREST's own code and the server's words become three plain values.
 * What those facts *mean* — the wording, and the difference between "there is no
 * project" and "the server refused" — is decided in [SyncRpcFailure], which is a
 * project file with no vendor in it. The caller above reads a project type and
 * never learns the SDK's — and the difference between "the session is gone" and
 * "the deployment is missing a migration" survives the trip.
 *
 * **A vendor seam.** This file and `SupabaseClientProvider` are the only two in
 * the project that name the Supabase SDK, which is what lets
 * `VendorSdkConfinementTest` state a rule an author can check against.
 */
class PostgrestSyncRpc(private val clients: SupabaseClientProvider) : SyncRpc {

    /**
     * The raw body is returned rather than a decoded value because the two calls
     * this makes have different shapes — an object and an array — and the parsing
     * that depends on their exact contents belongs with the caller that knows what
     * it asked for.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun call(function: String, params: JsonObject): String {
        val postgrest = client(function)
        return try {
            postgrest.rpc(function, params).data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A transport boundary is exactly where an unknown throwable is
            // expected: the SDK, Ktor and the platform socket layer each have
            // their own. The catch does not recover — it reads the vendor's
            // exception and hands the facts to the project's decision.
            throw SyncRpcFailure.refused(
                failure = SupabaseTransportFailure.classify(e),
                facts = SupabaseTransportFailure.facts(e),
                function = function,
                cause = e,
            )
        }
    }

    private suspend fun client(function: String): Postgrest = clients.client()?.postgrest
        ?: throw SyncRpcFailure.notConfigured(function)
}
