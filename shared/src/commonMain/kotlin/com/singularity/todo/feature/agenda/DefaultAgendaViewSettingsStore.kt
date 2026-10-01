package com.singularity.todo.feature.agenda

import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.agenda.domain.port.DefaultAgendaViewSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reads and writes the default agenda view setting.
 *
 * Contributes [SettingsSection.DefaultAgendaView] to the unified settings UI.
 * Registration: `single<SettingsContributor> { DefaultAgendaViewSettingsContributor(get()) }`.
 */
class DefaultAgendaViewSettingsStore(private val defaultAgendaView: DefaultAgendaViewSettingsRepository) {
    /**
     * Default agenda view — a single nullable ID.
     */
    fun observe(): Flow<SettingsSection.DefaultAgendaView> = defaultAgendaView.defaultViewId.map { viewId ->
        SettingsSection.DefaultAgendaView(viewId = viewId)
    }

    suspend fun process(intent: SettingsIntent.DefaultAgendaView) {
        when (intent) {
            is SettingsIntent.DefaultAgendaView.Update -> defaultAgendaView.setDefaultViewId(intent.viewId)
        }
    }
}
