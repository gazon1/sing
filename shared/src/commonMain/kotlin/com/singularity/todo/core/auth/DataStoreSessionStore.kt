package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.datastore.catchDataStoreIoError
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.sync.DeviceIdProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The pre-upgrade token store, and the current home of the device id.
 *
 * ## Why this still exists
 *
 * Two reasons, and only two. It holds a token written by an older build, which
 * [SecureSessionManager] moves into the keychain once and then erases — and it
 * holds the device id, which is not a secret and should not be in a keychain.
 *
 * ## Why writing tokens here is no longer a path
 *
 * [forgetTokens] is the only write left, and it erases: the migration needs the
 * erase half, and nothing else in the graph writes a token to plain
 * preferences any more. A caller reaching this class to *write* a token has
 * found a bug — `PlaintextTokenIsolationTest` fails if one appears.
 *
 * The other two writes — [getOrInitLocalOwnerId] and the local-session marker — are
 * **not** tokens and carry nothing secret. They are here for the same reason the device
 * id is: plain DataStore is where non-secret device state belongs, and the desktop
 * keychain pays a `secret-tool` subprocess per read to store something a preferences
 * file holds just as well.
 *
 * Device ID is lazily initialized on first [getOrInitDeviceId] call by reading from DataStore.
 * If absent, an ID is generated via [idGenerator], persisted, and returned.
 *
 * No [runBlocking] at construction — the ID is loaded asynchronously on first access.
 */
class DataStoreSessionStore(private val dataStore: DataStore<Preferences>, private val idGenerator: IdGenerator) :
    // The sync seam takes the device id from here — it is not a secret and is
    // stored in plain DataStore by design (DeviceIdProvider).
    DeviceIdProvider {

    companion object {
        val ACCESS_TOKEN = stringPreferencesKey("auth_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("auth_refresh_token")
        val USER_EMAIL = stringPreferencesKey("auth_user_email")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")

        /**
         * The owner id of this install's local, offline data. See [getOrInitLocalOwnerId].
         */
        val LOCAL_OWNER_ID = stringPreferencesKey("auth_local_owner_id")

        /** Whether this install is currently running without an account. */
        val LOCAL_SESSION = stringPreferencesKey("auth_local_session")

        /**
         * Marks an owner id as local, so it can never collide with a provider's.
         *
         * A provider account id is a UUID; this prefix means the two namespaces are
         * distinguishable by inspection. That matters at exactly one place — deciding
         * whether a row's owner can be moved to an account at all — and guessing it by
         * shape instead of by marker would be a second, weaker rule about the same
         * question.
         */
        const val LOCAL_OWNER_PREFIX = "local:"
    }

    val accessToken: Flow<String?> = dataStore.data
        .catchDataStoreIoError()
        .map { it[ACCESS_TOKEN] }
    val refreshToken: Flow<String?> = dataStore.data
        .catchDataStoreIoError()
        .map { it[REFRESH_TOKEN] }
    val userEmail: Flow<String?> = dataStore.data
        .catchDataStoreIoError()
        .map { it[USER_EMAIL] }

    // Lazily initialized on first getOrInitDeviceId() call — not blocking at construction.
    private var _deviceId: String? = null

    override suspend fun getOrInitDeviceId(): String {
        _deviceId?.let { return it }
        val prefs = dataStore.data
            .catchDataStoreIoError()
            .first()
        val stored = prefs[DEVICE_ID]
        val id = stored ?: idGenerator.next().also { newId ->
            dataStore.edit { it[DEVICE_ID] = newId }
        }
        _deviceId = id
        return id
    }

    /**
     * The owner id this install files offline data under, creating it on first use.
     *
     * ## Why the id is stored rather than derived
     *
     * Deriving it from the device id was the first design and it cannot express the one
     * fact that matters: whether the user has *chosen* to work without an account. A
     * derived id always exists, so a derived id would also restore an anonymous session
     * on a fresh install, and the screen where you choose between an account and no
     * account would never be shown to anyone.
     *
     * ## Why signing out does not erase it
     *
     * Signing out ends a session; it does not delete data. The rows under this id would
     * still be here, and an id nobody regenerates is the only thing that can still find
     * them. Erasing it here would orphan every task the user created offline — with no
     * error, no failed sign-out, and no way back. See ADR
     * `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`.
     */
    suspend fun getOrInitLocalOwnerId(): String {
        val prefs = dataStore.data
            .catchDataStoreIoError()
            .first()
        prefs[LOCAL_OWNER_ID]?.let { return it }
        val created = LOCAL_OWNER_PREFIX + idGenerator.next()
        dataStore.edit { it[LOCAL_OWNER_ID] = created }
        return created
    }

    /**
     * Records that this install is running without an account.
     *
     * Separate from the id itself, and cleared on sign-out while the id is not: this is
     * a statement about the *session*, the id is a statement about the *data*. Clearing
     * both would destroy the user's offline work; clearing neither would mean signing
     * out of an account dropped the user straight back into offline mode with no way to
     * choose a different account.
     */
    suspend fun markLocalSessionActive() {
        dataStore.edit { it[LOCAL_SESSION] = true.toString() }
    }

    /** Whether the last launch was in offline mode. False on a fresh install. */
    suspend fun isLocalSessionActive(): Boolean =
        dataStore.data
            .catchDataStoreIoError()
            .first()[LOCAL_SESSION] == true.toString()

    /**
     * Ends the local session marker, leaving [LOCAL_OWNER_ID] in place.
     *
     * @see markLocalSessionActive
     */
    suspend fun clearLocalSession() {
        dataStore.edit { prefs -> prefs.remove(LOCAL_SESSION) }
    }

    /**
     * Removes the plain-text token, email and refresh token.
     *
     * The only write left on this class: the migration erases the old copy
     * without touching the device id, which lives here and is still in use.
     */
    suspend fun forgetTokens() {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(USER_EMAIL)
        }
    }
}
