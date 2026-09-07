package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Device ID is lazily initialized on first access and persisted immediately.
 */
class DataStoreSessionStore(private val dataStore: DataStore<Preferences>) : SessionStore {

    companion object {
        val ACCESS_TOKEN = stringPreferencesKey("auth_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("auth_refresh_token")
        val USER_EMAIL = stringPreferencesKey("auth_user_email")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
    }

    override val accessToken: Flow<String?> = dataStore.data.map { it[ACCESS_TOKEN] }
    override val refreshToken: Flow<String?> = dataStore.data.map { it[REFRESH_TOKEN] }
    override val userEmail: Flow<String?> = dataStore.data.map { it[USER_EMAIL] }

    private val _deviceId = MutableStateFlow("")
    override val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val initMutex = Mutex()

    /** Must be called (and awaited) before reading [deviceId] for the first time. */
    private suspend fun ensureInitialized() {
        if (_deviceId.value.isNotEmpty()) return
        initMutex.withLock {
            if (_deviceId.value.isNotEmpty()) return // another coroutine won the race
            val prefs = dataStore.data.first()
            val stored = prefs[DEVICE_ID]
            val id = stored ?: generateDeviceId().also { newId ->
                dataStore.edit { it[DEVICE_ID] = newId }
            }
            _deviceId.value = id
        }
    }

    /**
     * Returns the device ID, initializing it if necessary.
     * Safe to call from [CurrentUser] or [AuthRepository] initialization.
     */
    override suspend fun getOrInitDeviceId(): String {
        ensureInitialized()
        return _deviceId.value
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
        _deviceId.value = id
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(USER_EMAIL)
        }
    }

    private fun generateDeviceId(): String = java.util.UUID.randomUUID().toString()
}
