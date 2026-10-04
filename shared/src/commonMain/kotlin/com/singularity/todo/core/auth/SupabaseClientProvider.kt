package com.singularity.todo.core.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the one [SupabaseClient] the app talks to, and rebuilds it when the
 * configured project changes.
 *
 * ## Why the client is not built in the DI graph
 *
 * The project URL and key are the user's to supply, so "there is no server yet"
 * is a state a fresh install starts in. A `single { SupabaseClient(...) }` binding
 * would have to be created at graph start, before anyone has entered a URL — which
 * means it either throws on first launch or is created against a value that did
 * not exist yet and is never refreshed. Both are worse than asking for the client
 * at the point of use and answering "not configured" honestly.
 *
 * That answer is [client] returning null. Callers treat it as a real state rather
 * than an error: a user who has not signed in yet has not failed at anything.
 *
 * ## Why the client is cached
 *
 * Every client owns an HTTP connection pool and a coroutine scope, and its
 * `Auth` plugin keeps the session. Handing out a fresh one per request would open
 * a socket per sync, and — worse — give two requests two different sessions, so a
 * sign-in in flight would not be visible to the push already in progress.
 *
 * The cache is keyed on the configuration, so switching projects cannot hand back
 * a client still pointing at the old one. [forget] closes the outgoing client
 * rather than dropping the reference: an HTTP client that is merely unreferenced
 * keeps its pool and its scope alive until the process ends.
 */
class SupabaseClientProvider(private val resolver: SupabaseConfigResolver, private val store: SecureStorage) {
    private val lock = Mutex()
    private var cachedConfig: SupabaseConfig? = null
    private var cachedClient: SupabaseClient? = null

    /**
     * The client for the currently configured project, or null when none is
     * configured.
     *
     * Reads the secure store on every call rather than trusting a cached config,
     * because another screen can change it; the *client* is what is expensive, so
     * that is what the cache holds.
     */
    suspend fun client(): SupabaseClient? = lock.withLock {
        val config = resolver.resolve(store) ?: return@withLock null
        cachedClient?.takeIf { cachedConfig == config } ?: build(config)
    }

    /**
     * Points the app at [config] and stores it, so it survives a restart.
     *
     * Discards the current client without closing it: the sign-in that triggered
     * this may still be completing against it, and closing it mid-flight would
     * cancel the very call that produced the configuration.
     */
    suspend fun configure(config: SupabaseConfig) {
        lock.withLock {
            resolver.store(store, config)
            cachedConfig = config
            cachedClient = build(config)
        }
    }

    /** Forgets the configuration and closes the client that was using it. */
    suspend fun forget() {
        val doomed = lock.withLock {
            resolver.clear(store)
            cachedConfig = null
            cachedClient.also { cachedClient = null }
        }
        doomed?.close()
    }

    private fun build(config: SupabaseConfig): SupabaseClient = createSupabaseClient(config.url, config.anonKey) {
        install(Auth)
        install(Postgrest)
    }
}
