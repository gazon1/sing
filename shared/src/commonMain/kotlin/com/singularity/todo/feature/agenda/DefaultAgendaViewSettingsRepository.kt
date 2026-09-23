package com.singularity.todo.feature.agenda

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Contract for the default agenda view setting.
 */
interface DefaultAgendaViewSettingsRepository {

    val defaultViewId: Flow<SavedAgendaViewId?>
    suspend fun setDefaultViewId(id: SavedAgendaViewId?)
}

/**
 * Production [DefaultAgendaViewSettingsRepository] backed by DataStore.
 */
class DataStoreDefaultAgendaViewSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : DefaultAgendaViewSettingsRepository {

    companion object {
        val DEFAULT_VIEW_ID = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.AGENDA, "default_view_id"))
    }

    override val defaultViewId: Flow<SavedAgendaViewId?> = dataStore.data.map {
        it[DEFAULT_VIEW_ID]?.let { raw -> runCatching { SavedAgendaViewId.fromString(raw) }.getOrNull() }
    }

    override suspend fun setDefaultViewId(id: SavedAgendaViewId?) {
        dataStore.edit {
            if (id == null) {
                it.remove(DEFAULT_VIEW_ID)
            } else {
                it[DEFAULT_VIEW_ID] = id.raw
            }
        }
    }
}
