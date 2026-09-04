package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Contract for session token persistence.
 */
interface SessionStore {
    val accessToken: Flow<String?>
    val refreshToken: Flow<String?>
    val userEmail: Flow<String?>
    val deviceId: Flow<String>

    suspend fun save(session: Session.SignedIn)
    suspend fun saveDeviceId(id: String)
    suspend fun clear()
}

/**
 * Production [SessionStore] backed by DataStore.
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
    override val deviceId: Flow<String> = dataStore.data.map { it[DEVICE_ID] ?: generateDeviceId() }

    override suspend fun save(session: Session.SignedIn) {
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = session.accessToken
            prefs[REFRESH_TOKEN] = session.refreshToken
            prefs[USER_EMAIL] = session.email
        }
    }

    override suspend fun saveDeviceId(id: String) {
        dataStore.edit { it[DEVICE_ID] = id }
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
