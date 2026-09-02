package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists auth session tokens in DataStore.
 */
class SessionStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        val ACCESS_TOKEN = stringPreferencesKey("auth_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("auth_refresh_token")
        val USER_EMAIL = stringPreferencesKey("auth_user_email")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
    }

    val accessToken: Flow<String?> = dataStore.data.map { it[ACCESS_TOKEN] }
    val refreshToken: Flow<String?> = dataStore.data.map { it[REFRESH_TOKEN] }
    val userEmail: Flow<String?> = dataStore.data.map { it[USER_EMAIL] }
    val deviceId: Flow<String> = dataStore.data.map { it[DEVICE_ID] ?: generateDeviceId() }

    fun accessTokenBlocking(): String? = kotlinx.coroutines.runBlocking {
        dataStore.data.first()[ACCESS_TOKEN]
    }

    fun refreshTokenBlocking(): String? = kotlinx.coroutines.runBlocking {
        dataStore.data.first()[REFRESH_TOKEN]
    }

    fun deviceIdBlocking(): String = kotlinx.coroutines.runBlocking {
        dataStore.data.first()[DEVICE_ID] ?: generateDeviceId().also { saveDeviceId(it) }
    }

    suspend fun save(session: Session.SignedIn) {
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = session.accessToken
            prefs[REFRESH_TOKEN] = session.refreshToken
            prefs[USER_EMAIL] = session.email
        }
    }

    suspend fun saveDeviceId(id: String) {
        dataStore.edit { it[DEVICE_ID] = id }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(USER_EMAIL)
        }
    }

    private fun generateDeviceId(): String = java.util.UUID.randomUUID().toString()
}
