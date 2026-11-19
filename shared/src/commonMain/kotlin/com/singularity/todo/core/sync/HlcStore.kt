package com.singularity.todo.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.datastore.catchDataStoreIoError
import com.singularity.todo.core.error.runCatchingCancellable
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

    /**
     * The last clock that was stored, or `null` when none was.
     *
     * A stored value that is not a clock does not fail the read. [Hlc.parse] is
     * total — it wraps whatever it is given and never throws — so a corrupted
     * file comes back as a degenerate clock (`physical` 0, `counter` 0) rather
     * than as `null`. The degenerate clock is harmless: it sits at zero, and the
     * next tick takes the wall clock, so nothing drifts backwards.
     *
     * The `runCatchingCancellable` is here for the day [Hlc.parse] stops being
     * total: a parse that threw an ordinary exception would become `null` (no
     * clock), and a parse that threw a cancellation would propagate it, because a
     * cancelled read has to look cancelled rather than empty.
     */
    override suspend fun load(): Hlc? =
        dataStore.data
            .catchDataStoreIoError()
            .first()[LAST_HLC]
            ?.let { runCatchingCancellable { Hlc.parse(it) }.getOrNull() }

    override suspend fun save(hlc: Hlc) {
        dataStore.edit { it[LAST_HLC] = hlc.encoded }
    }
}
