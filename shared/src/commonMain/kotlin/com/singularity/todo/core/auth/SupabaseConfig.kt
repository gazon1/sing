package com.singularity.todo.core.auth

import com.singularity.todo.core.ids.IdGenerator

/**
 * Where the app should talk to, and as whom.
 *
 * ## The key is not a secret, and saying so is the point
 *
 * The Supabase **anon** key is a publishable identifier: it ships inside every copy
 * of the app and is meant to be readable by anyone who has one. The **service-role**
 * key is not, and it never appears here, in the transport, or in any build
 * configuration. Every server entry point runs as the authenticated role under
 * row-level policies, so nothing the client holds is capable of reading another
 * account's rows.
 *
 * That is why [anonKey] lives in a config object at all. A future reader looking for
 * something to protect here should read this paragraph and move on to the token
 * store, which is where the real secret is.
 */
data class SupabaseConfig(val url: String, val anonKey: String) {
    init {
        require(url.isNotBlank()) { "supabase url must not be blank" }
        require(anonKey.isNotBlank()) { "supabase anon key must not be blank" }
    }
}

/**
 * Build-time configuration, present only for development.
 *
 * The **only** place a URL and key are allowed to come from without the user having
 * entered them. It exists so a developer can run the app against a real project
 * without typing a URL on first launch, and it returns null in a release build so
 * that "the app cannot reach the server" is a state the app has to handle rather than
 * one it can never enter.
 *
 * The distinction is the reason this is a separate type from [SupabaseConfig]: a
 * release build physically cannot produce one of these.
 */
data class BuildTimeSupabaseConfig(val url: String, val anonKey: String)

/**
 * The build-time configuration, or null when there is none.
 *
 * Android: `BuildConfig` fields, which the release build type leaves empty.
 * JVM/Desktop: system properties, which the packaged app does not set.
 */
expect fun buildTimeSupabaseConfig(): BuildTimeSupabaseConfig?

/**
 * Resolves the configuration to use, preferring what the user or installer supplied.
 *
 * The order is not arbitrary. A value in the secure store is the user's own project —
 * a self-hosted or private one — and it must win over a developer's leftover build
 * flag, or a debug build pointed at a test project would silently override the real
 * thing. The build-time value is a *fallback*, which is the only honest description of
 * it: it exists so a fresh clone runs, not so a shipped app has credentials.
 */
class SupabaseConfigResolver(
    private val idGenerator: IdGenerator,
    private val buildTime: () -> BuildTimeSupabaseConfig? = ::buildTimeSupabaseConfig,
) {
    companion object {
        /** Keys in the secure store. Public on purpose — they are not secrets either. */
        const val KEY_URL = "supabase.url"
        const val KEY_ANON_KEY = "supabase.anon_key"
    }

    /**
     * The stored configuration, or null when nothing has been configured.
     *
     * Null is a real state and not an error: a fresh install has no server, and the
     * app has to be able to say so rather than throwing on the first frame.
     */
    suspend fun resolve(store: SecureStorage): SupabaseConfig? {
        val stored = store.read(KEY_URL)?.let { url ->
            store.read(KEY_ANON_KEY)?.takeIf { it.isNotBlank() }?.let { anonKey ->
                SupabaseConfig(url = url, anonKey = anonKey)
            }
        }
        if (stored != null) return stored

        return buildTime()?.let { SupabaseConfig(url = it.url, anonKey = it.anonKey) }
    }

    /**
     * Stores a configuration, replacing whatever was there.
     *
     * Rejects a blank half rather than storing it, because a stored URL with no key is
     * indistinguishable from "not configured" until the first request fails, and the
     * failure arrives as a network error that looks like a server problem.
     */
    suspend fun store(store: SecureStorage, config: SupabaseConfig) {
        require(config.url.isNotBlank() && config.anonKey.isNotBlank()) {
            "refusing to store a half-configured supabase config"
        }
        store.write(KEY_URL, config.url)
        store.write(KEY_ANON_KEY, config.anonKey)
    }

    /**
     * Forgets the stored configuration, so the next [resolve] falls back to the
     * build-time value.
     *
     * Sign-out calls this: leaving another account's project configured on a shared
     * device is the kind of thing nobody remembers to clean up.
     */
    suspend fun clear(store: SecureStorage) {
        store.delete(KEY_URL)
        store.delete(KEY_ANON_KEY)
    }
}

/**
 * The narrow slice of secure storage this needs.
 *
 * An interface rather than [com.singularity.todo.core.security.SecureStoragePort] so
 * the config layer does not depend on the whole secure-storage abstraction, and so a
 * test can supply a map without implementing a keyring.
 */
interface SecureStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun delete(key: String)
}
