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

    /**
     * Writes the clock, unless the stored one is newer.
     *
     * The stored clock is the device's promise that it will never mint a clock older
     * than this one, so a write that carries an *older* clock would undo that promise:
     * after a restart the device would restore a clock older than one it already
     * issued, and the next patch could carry a timestamp older than a timestamp the
     * server already holds. The write is therefore dropped rather than applied.
     *
     * The check is inside the `edit` block, so the read and the write are one
     * transaction: two overlapping writes cannot both see the old value and both
     * believe they are the newer one.
     *
     * A stored value that is not a clock comes back as a degenerate clock at zero (see
     * [load]), and any real clock is newer than that — so a corrupted file does not
     * hold the clock back.
     */
    override suspend fun save(hlc: Hlc) {
        dataStore.edit { preferences ->
            val current = preferences[LAST_HLC]?.let { Hlc.parse(it) }
            if (current == null || hlc > current) {
                preferences[LAST_HLC] = hlc.encoded
            }
        }
    }
}
