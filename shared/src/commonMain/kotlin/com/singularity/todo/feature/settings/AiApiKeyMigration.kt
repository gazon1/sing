package com.singularity.todo.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.feature.ai.OpenAiConfig
import kotlinx.coroutines.flow.first

/**
 * One-shot migration of the OpenAI API key from DataStore to [SecureStoragePort].
 *
 * Versions of the app stored the API key in DataStore (under [LEGACY_DATASTORE_KEY]).
 * Newer versions store it only in the secure storage backed by the platform
 * keychain (Android Keystore / Linux libsecret). When this migration runs the
 * first time, it copies the existing key (if any) into [SecureStoragePort] and
 * clears the DataStore entry.
 *
 * Call from the application entry point once during startup, BEFORE the first
 * read of the secure storage. Subsequent runs are no-ops.
 */
object AiApiKeyMigration {
    /** DataStore key used by the legacy app versions. */
    const val LEGACY_DATASTORE_KEY = "ai_api_key"

    /**
     * Idempotent — safe to call on every startup. Returns `true` if a key
     * was migrated in this call, `false` otherwise.
     */
    suspend fun run(
        dataStore: DataStore<Preferences>,
        secureStorage: SecureStoragePort,
    ): Boolean {
        // Don't overwrite an already-configured secure key.
        val existing = secureStorage.read(OpenAiConfig.KEY_OPENAI)
        if (!existing.isNullOrBlank()) return false

        val legacy = dataStore.data.first()[stringPreferencesKey(LEGACY_DATASTORE_KEY)]
            ?: return false

        secureStorage.write(OpenAiConfig.KEY_OPENAI, legacy)
        dataStore.edit { it.remove(stringPreferencesKey(LEGACY_DATASTORE_KEY)) }
        return true
    }
}