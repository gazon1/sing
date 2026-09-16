package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.compositionLocalOf

/**
 * Provides [AgendaNavigator] to all Agenda feature screens.
 * Must be provided by [AgendaNavGraph][com.singularity.todo.feature.agenda.presentation.nav.AgendaNavGraph].
 */
val LocalAgendaNavigator = compositionLocalOf<AgendaNavigator> {
    error("AgendaNavigator not provided — wrap with AgendaNavGraph")
}
