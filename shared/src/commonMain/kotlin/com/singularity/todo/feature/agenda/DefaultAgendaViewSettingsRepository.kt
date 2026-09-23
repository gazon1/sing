package com.singularity.todo.feature.agenda

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.settings.BaseSettingsRepository
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
 * Null [SavedAgendaViewId] removes the preference key (same behaviour as [BaseSettingsRepository]-based
 * repos for nullable values).
 */
class DataStoreDefaultAgendaViewSettingsRepository(
    dataStore: DataStore<Preferences>,
) : BaseSettingsRepository(dataStore), DefaultAgendaViewSettingsRepository {

    private val rawPref = nullableStringPref(nsKey(SettingsNamespace.AGENDA, "default_view_id"))

    override val defaultViewId: Flow<SavedAgendaViewId?> = rawPref.flow.map { raw ->
        raw?.let { runCatching { SavedAgendaViewId.fromString(it) }.getOrNull() }
    }

    override suspend fun setDefaultViewId(id: SavedAgendaViewId?) {
        rawPref.set(id?.raw)
    }
}
