package com.singularity.todo.core.draft

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import com.singularity.todo.core.datastore.catchDataStoreIoError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * Production [DraftStore] backed by [DataStore].
 *
 * Drafts are stored as JSON strings under [stringPreferencesKey] entries.
 * Uses [StableJson] for serialization with classDiscriminator for polymorphic types.
 */
class DataStoreDraftStore(
    private val dataStore: DataStore<Preferences>,
    private val logger: Logger = Logger.withTag("DataStoreDraftStore"),
) : DraftStore {

    override suspend fun <T> load(key: String, deserializer: DeserializationStrategy<T>): T? {
        val data = dataStore.data
            .catchDataStoreIoError { e ->
                logger.w(tag = "DraftStore") { "DataStore read failed for key=$key, treating as empty: $e" }
            }
            .first()
        val json = data[stringPreferencesKey(key)]
            ?: return null
        return runCatchingCancellable {
            StableJson.decodeFromString(deserializer, json)
        }.getOrNull()
            .also { result ->
                if (result == null) {
                    logger.w(tag = "DraftStore") { "decode failed for key=$key, draft dropped" }
                }
            }
    }

    override suspend fun <T> save(key: String, value: T, serializer: SerializationStrategy<T>) {
        val json = StableJson.encodeToString(serializer, value)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = json
        }
    }

    override suspend fun clear(key: String) {
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
        }
    }
}
