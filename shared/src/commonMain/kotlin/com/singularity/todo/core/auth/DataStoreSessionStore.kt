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
 * [SecureSessionStore] moves into the keychain once and then erases — and it
 * holds the device id, which is not a secret and should not be in a keychain.
 *
 * ## Why writing tokens here is no longer a path
 *
 * [forgetTokens] is the only write left, and it erases: the migration needs the
 * erase half, and nothing else in the graph writes a token to plain
 * preferences any more. A caller reaching this class to *write* a token has
 * found a bug — `PlaintextTokenIsolationTest` fails if one appears.
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
