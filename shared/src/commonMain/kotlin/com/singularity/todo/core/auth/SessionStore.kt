package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.ids.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Contract for session token persistence.
 */
interface SessionStore {
    val accessToken: Flow<String?>
    val refreshToken: Flow<String?>
    val userEmail: Flow<String?>
    val deviceId: StateFlow<String>

    /** Initializes and returns the device ID. Safe to call multiple times. */
    suspend fun getOrInitDeviceId(): String
    suspend fun save(session: Session.SignedIn)
    suspend fun saveDeviceId(id: String)
    suspend fun clear()
}

/**
 * Production [SessionStore] backed by DataStore.
 *
 * Device ID is lazily initialized on first [getOrInitDeviceId] call by reading from DataStore.
 * If absent, an ID is generated via [idGenerator], persisted, and returned.
 *
 * No [runBlocking] at construction — the ID is loaded asynchronously on first access.
 * [deviceId] returns an empty string until initialized.
 */
class DataStoreSessionStore(private val dataStore: DataStore<Preferences>, private val idGenerator: IdGenerator) :
    SessionStore {

    companion object {
        val ACCESS_TOKEN = stringPreferencesKey("auth_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("auth_refresh_token")
        val USER_EMAIL = stringPreferencesKey("auth_user_email")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
    }

    override val accessToken: Flow<String?> = dataStore.data.map { it[ACCESS_TOKEN] }
    override val refreshToken: Flow<String?> = dataStore.data.map { it[REFRESH_TOKEN] }
    override val userEmail: Flow<String?> = dataStore.data.map { it[USER_EMAIL] }

    // Lazily initialized on first getOrInitDeviceId() call — not blocking at construction.
    private var _deviceId: String? = null
    private val _deviceIdFlow = MutableStateFlow("")
    override val deviceId: StateFlow<String> = _deviceIdFlow.asStateFlow()

    override suspend fun getOrInitDeviceId(): String {
        _deviceId?.let { return it }
        val prefs = dataStore.data.first()
        val stored = prefs[DEVICE_ID]
        val id = stored ?: idGenerator.next().also { newId ->
            dataStore.edit { it[DEVICE_ID] = newId }
        }
        _deviceId = id
        _deviceIdFlow.value = id
        return id
    }

    override suspend fun save(session: Session.SignedIn) {
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = session.accessToken
            prefs[REFRESH_TOKEN] = session.refreshToken
            prefs[USER_EMAIL] = session.email
        }
    }

    override suspend fun saveDeviceId(id: String) {
        dataStore.edit { it[DEVICE_ID] = id }
        _deviceId = id
        _deviceIdFlow.value = id
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(USER_EMAIL)
        }
    }
}
