package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.error.AppError
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
 * The vendor's failures are classified here, where the vendor's types are, and
 * what leaves is the class and the server's own words: a refused identity, a
 * function that is not in the schema cache, a call that never arrived, or a
 * server that said no. The caller above reads a project type and never learns
 * the SDK's — and the difference between "the session is gone" and "the
 * deployment is missing a migration" survives the trip.
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
            // their own. The catch does not recover — it classifies and rethrows.
            throw SupabaseTransportFailure.toTransportException(e, "$function failed")
        }
    }

    private suspend fun client(function: String): Postgrest = clients.client()?.postgrest
        ?: throw AppError.Validation(
            "No Supabase project is configured, so $function cannot be called.",
            code = "sync.not_configured",
        )
}
