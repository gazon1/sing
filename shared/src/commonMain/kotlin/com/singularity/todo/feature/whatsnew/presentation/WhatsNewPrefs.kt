package com.singularity.todo.feature.whatsnew.presentation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * DataStore-backed persistence for the WhatsNew screen.
 *
 * Records a hash of the last-shown payload so the screen does not re-appear
 * after a single dismiss unless the payload content has actually changed.
 *
 * Persists across app restarts; cleared on app data reset.
 *
 * Use [WhatsNewPrefs.create] in Koin modules to construct from a `DataStore<Preferences>`.
 */
class WhatsNewPrefs private constructor(private val dataStore: DataStore<Preferences>) {

    /**
     * Returns true if the WhatsNew sheet should be shown for [payload].
     *
     * Returns false when the [lastShownHash] equals [payload.hashCode] — the same
     * payload was already dismissed.
     */
    suspend fun shouldShow(payload: String?): Boolean {
        if (payload.isNullOrBlank()) return false
        val currentHash = payload.hashCode().toString()
        return currentHash != lastShownHash()
    }

    /** Records the payload hash so it is not re-shown next startup. */
    suspend fun markShown(payload: String) {
        val currentHash = payload.hashCode().toString()
        dataStore.edit { it[KEY_LAST_HASH] = currentHash }
    }

    private suspend fun lastShownHash(): String = dataStore.data.first()[KEY_LAST_HASH] ?: ""

    companion object {
        private val KEY_LAST_HASH = stringPreferencesKey("whatsnew.last_shown_hash")

        fun create(dataStore: DataStore<Preferences>): WhatsNewPrefs = WhatsNewPrefs(dataStore)
    }
}
