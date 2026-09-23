package com.singularity.todo.feature.agenda

import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow

/**
 * Marker interface for the Default Agenda View settings contributor.
 * Used by [com.singularity.todo.feature.settings.SettingsViewModel] to resolve the
 * contributor without type erasure.
 */
interface DefaultAgendaViewContributor : SettingsContributor<SettingsSection.DefaultAgendaView, SettingsIntent.DefaultAgendaView>

/**
 * Contributes the Default Agenda View settings section to the unified settings UI.
 *
 * Registration: `single<SettingsContributor> { DefaultAgendaViewSettingsContributor(get()) }`.
 */
class DefaultAgendaViewSettingsContributor(
    private val store: DefaultAgendaViewSettingsStore,
) : DefaultAgendaViewContributor {

    override val section: SettingsSection.DefaultAgendaView = SettingsSection.DefaultAgendaView()

    override fun observe(): Flow<SettingsSection.DefaultAgendaView> = store.observe()

    override suspend fun process(intent: SettingsIntent.DefaultAgendaView) {
        store.process(intent)
    }
}
