package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.ids.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
 * The pre-upgrade store, and the current home of the device id.
 *
 * ## Why this still exists
 *
 * Two reasons, and only two. It holds a token written by an older build, which
 * [SecureSessionStore] moves into the keychain once and then erases — and it
 * holds the device id, which is not a secret and should not be in a keychain.
 *
 * ## Why writing tokens here is no longer a path
 *
 * [save] and [clear] remain because the migration needs the erase half, and
 * because removing the interface method would mean a subclass of a type this
 * class no longer has a reason to be. Nothing in the DI graph binds this as a
 * [SessionStore] any more; `PlaintextTokenIsolationTest` fails if one appears.
 * A caller reaching this class to *write* a token has found a bug.
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

    override val accessToken: Flow<String?> = dataStore.data
        .catchIOExceptionEmitEmpty()
        .map { it[ACCESS_TOKEN] }
    override val refreshToken: Flow<String?> = dataStore.data
        .catchIOExceptionEmitEmpty()
        .map { it[REFRESH_TOKEN] }
    override val userEmail: Flow<String?> = dataStore.data
        .catchIOExceptionEmitEmpty()
        .map { it[USER_EMAIL] }

    // Lazily initialized on first getOrInitDeviceId() call — not blocking at construction.
    private var _deviceId: String? = null
    private val _deviceIdFlow = MutableStateFlow("")
    override val deviceId: StateFlow<String> = _deviceIdFlow.asStateFlow()

    override suspend fun getOrInitDeviceId(): String {
        _deviceId?.let { return it }
        val prefs = dataStore.data
            .catchIOExceptionEmitEmpty()
            .first()
        val stored = prefs[DEVICE_ID]
        val id = stored ?: idGenerator.next().also { newId ->
            dataStore.edit { it[DEVICE_ID] = newId }
        }
        _deviceId = id
        _deviceIdFlow.value = id
        return id
    }

    /**
     * Removes the plain-text token, email and refresh token.
     *
     * Separate from [clear] so the migration can erase the old copy without
     * touching the device id, which lives here and is still in use.
     */
    suspend fun forgetTokens() {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(USER_EMAIL)
        }
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

/**
 * Emits [emptyPreferences] when [java.io.IOException] is thrown (e.g. corrupted DataStore file),
 * re-throwing all other exceptions.
 */
private fun Flow<Preferences>.catchIOExceptionEmitEmpty(): Flow<Preferences> =
    catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
