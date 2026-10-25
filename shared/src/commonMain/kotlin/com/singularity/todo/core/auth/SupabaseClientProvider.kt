package com.singularity.todo.core.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
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
open class SupabaseClientProvider(
    private val resolver: SupabaseConfigResolver,
    private val store: SecureStorage,
    /**
     * Where the auth plugin keeps the session.
     *
     * The plugin saves and loads the session wherever this says, and the default would
     * be the SDK's own settings store — a plain-text file, which is the very thing
     * [DataStoreSessionStore] was moved out of.
     */
    private val sessionStore: SecureSessionManager,
) {
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
     *
     * ## Why the config is remembered together with the client
     *
     * The cache is keyed on the configuration, and the key has to be stored next to
     * the value it describes. Building a client without recording the config it was
     * built for leaves the key empty, the comparison against the resolved config
     * never matches, and every call builds a *new* client.
     *
     * That is not a performance detail. The session lives inside the client's
     * `Auth` plugin: a sign-in fills the plugin of the client it ran against, and
     * a request made through a different client instance finds an empty plugin and
     * falls back to the anon key. The symptom is a signed-in user whose requests
     * arrive as a stranger — which for the backup feature means a row-level
     * security policy refusing an upload the user is entitled to make.
     */
    open suspend fun client(): SupabaseClient? = lock.withLock {
        val config = resolver.resolve(store) ?: return@withLock null
        cachedClient?.takeIf { cachedConfig == config }
            ?: build(config).also {
                cachedConfig = config
                cachedClient = it
            }
    }

    /**
     * Points the app at [config] and stores it, so it survives a restart.
     *
     * Discards the current client without closing it: the sign-in that triggered
     * this may still be completing against it, and closing it mid-flight would
     * cancel the very call that produced the configuration.
     */
    open suspend fun configure(config: SupabaseConfig) {
        lock.withLock {
            resolver.store(store, config)
            cachedConfig = config
            cachedClient = build(config)
        }
    }

    /** Forgets the configuration and closes the client that was using it. */
    open suspend fun forget() {
        val doomed = lock.withLock {
            resolver.clear(store)
            cachedConfig = null
            cachedClient.also { cachedClient = null }
        }
        doomed?.close()
    }

    /**
     * The provider's auth plugin, or null when no project is configured.
     *
     * The seam's way of reaching the plugin. The plugin is an **interface**, so a test
     * can substitute it — and this class is already open with open methods for exactly
     * that, which is why the seam's own tests can exist at all.
     */
    open suspend fun auth(): Auth? = client()?.auth

    private fun build(config: SupabaseConfig): SupabaseClient = createSupabaseClient(config.url, config.anonKey) {
        install(Auth) {
            // The one session both sides read, in the app's keychain. Left to itself
            // the plugin would keep a second copy in its own settings store.
            sessionManager = sessionStore
            // The plugin must not read the session while the client is being built:
            // whoever builds the client first is not necessarily the restore, and a
            // migration that runs there is a migration outside the restore's bound.
            autoLoadFromStorage = false
            // The application owns the refresh. A plugin that refreshes on its own
            // republishes a session the application did not ask for.
            alwaysAutoRefresh = false
            // What the plugin is given, it keeps. The store is the credential store.
            autoSaveToStorage = true
        }
        install(Postgrest)
        // The backup feature's remote half: the archive goes into a Storage bucket.
        install(Storage)
        // The Google grant's remote half: the server redeems the one-time code and
        // holds the refresh token. The SDK carries the session, so the call is made
        // as the signed-in user and the server can bind the grant to that account.
        install(Functions)
    }
}
