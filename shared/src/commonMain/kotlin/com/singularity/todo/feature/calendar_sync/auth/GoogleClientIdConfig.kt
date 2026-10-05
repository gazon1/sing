package com.singularity.todo.feature.calendar_sync.auth

import com.singularity.todo.core.auth.SecureStorage

/**
 * The OAuth client id a Google sign-in should use.
 *
 * @param clientId the "installed application" client id, e.g. `…apps.googleusercontent.com`.
 */
data class GoogleClientIdConfig(val clientId: String) {
    init {
        require(clientId.isNotBlank()) { "a blank Google client id is not a configuration" }
    }
}

/**
 * Build-time configuration, present only for development and for builds whose packager
 * chose to bake the id in.
 *
 * ## The client id is not a secret
 *
 * This is worth stating plainly because the instinct on seeing a Google credential is to
 * treat it like one. It is not. An OAuth *client id* identifies the application; it ships
 * inside every copy of every app that uses Google sign-in and is designed to be read by
 * anyone who unzips the APK. Google's own guidance for installed/native clients is that
 * the accompanying **client secret is not confidential** either — a native app cannot keep
 * one, so treating it as a secret buys nothing and breaks legitimate installs. What is
 * secret, and what this project keeps in the platform keystore, is the **refresh token**
 * handed back at the end of consent; see [GoogleCredentialStore].
 *
 * So the client id may come from `local.properties` at build time, and the release build
 * may embed it. What may not be embedded is the token, and it is not.
 */
data class BuildTimeGoogleClientConfig(val clientId: String)

/**
 * The build-time client id, or null when the build has none.
 *
 * Android: `BuildConfig`, which is populated from `local.properties` and is empty for a
 * build made without one. JVM/Desktop: a system property, which the packaged application
 * does not set.
 */
expect fun buildTimeGoogleClientId(): BuildTimeGoogleClientConfig?

/**
 * Resolves which OAuth client id to use, preferring what the user typed.
 *
 * ## The order, and why it is the other way round from Supabase
 *
 * [com.singularity.todo.core.auth.SupabaseConfigResolver] lets a stored value beat the
 * build-time one, because a developer's leftover `local.properties` must not hijack the
 * server a real user configured. Here the opposite is right. A user who pastes a client id
 * is standing in front of a *specific Google Cloud project* — usually their own, with its
 * own calendars — and the id baked into the build belongs to the packager's project. Their
 * paste is about the data, and should win.
 *
 * A Google Calendar sync writes into a real account. Sending a user's events to a project
 * the packager controls is not a misconfiguration, it is a disclosure, so the resolution
 * order is a security decision rather than a convenience one.
 */
class GoogleClientIdResolver(private val buildTime: () -> BuildTimeGoogleClientConfig? = ::buildTimeGoogleClientId) {

    companion object {
        /** Key in the secure store. A client id is not a secret; this is namespacing, not secrecy. */
        const val KEY_CLIENT_ID = "google.calendar_client_id"
    }

    /**
     * The client id to use, or null when neither a stored nor a build-time one exists.
     *
     * Null is the honest "this build cannot sign in" state, and the sign-in screen has to
     * render it as a prompt to configure rather than as an error.
     */
    suspend fun resolve(store: SecureStorage): GoogleClientIdConfig? {
        val stored = store.read(KEY_CLIENT_ID)?.takeIf { it.isNotBlank() }
        if (stored != null) return GoogleClientIdConfig(stored)
        return buildTime()?.let { GoogleClientIdConfig(it.clientId) }
    }

    /**
     * Stores [clientId], replacing whatever was there.
     *
     * Rejects a blank value rather than storing it: a stored blank and a stored client id
     * are indistinguishable at the next read, and the failure would surface as an OAuth
     * error from Google several screens later.
     */
    suspend fun store(store: SecureStorage, clientId: String) {
        require(clientId.isNotBlank()) { "refusing to store a blank Google client id" }
        store.write(KEY_CLIENT_ID, clientId)
    }

    /** Forgets the stored id, so the build-time one applies again. */
    suspend fun clear(store: SecureStorage) {
        store.delete(KEY_CLIENT_ID)
    }
}
