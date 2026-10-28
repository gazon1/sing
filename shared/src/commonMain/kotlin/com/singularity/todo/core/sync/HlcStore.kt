package com.singularity.todo.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.datastore.catchDataStoreIoError
import kotlinx.coroutines.flow.first

interface HlcStore {
    suspend fun load(): Hlc?
    suspend fun save(hlc: Hlc)
}

typealias HlcStorage = HlcStore
typealias HlcPersistence = HlcStore
typealias HlcPersistenceStore = HlcStore
typealias HlcRepository = HlcStore
typealias LastHlcStore = HlcStore

class DataStoreHlcStore(private val dataStore: DataStore<Preferences>) : HlcStore {
    companion object {
        val LAST_HLC = stringPreferencesKey("sync_last_hlc")
    }

    override suspend fun load(): Hlc? =
        dataStore.data
            .catchDataStoreIoError()
            .first()[LAST_HLC]
            ?.let { runCatching { Hlc.parse(it) }.getOrNull() }

    override suspend fun save(hlc: Hlc) {
        dataStore.edit { it[LAST_HLC] = hlc.encoded }
    }
}
