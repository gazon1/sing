package com.singularity.todo.core.draft

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy

/**
 * Production [DraftStore] backed by [DataStore].
 *
 * Drafts are stored as JSON strings under [stringPreferencesKey] entries.
 * Uses [StableJson] for serialization with classDiscriminator for polymorphic types.
 */
class DataStoreDraftStore(
    private val dataStore: DataStore<Preferences>,
) : DraftStore {

    override suspend fun <T> load(key: String, deserializer: DeserializationStrategy<T>): T? {
        val json = dataStore.data.first()[stringPreferencesKey(key)] ?: return null
        return runCatching {
            StableJson.decodeFromString(deserializer, json)
        }.getOrNull()
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
