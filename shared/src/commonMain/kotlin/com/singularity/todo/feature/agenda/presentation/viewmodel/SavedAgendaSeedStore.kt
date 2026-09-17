package com.singularity.todo.feature.agenda.presentation.viewmodel

import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Temporary holder for the agenda definition seed when creating a new saved view.
 *
 * When the user taps the BookmarkAdd button in the agenda, the [AgendaViewModel]
 * writes the current [AgendaDefinition] here, then navigates to [SavedAgendaCreate].
 * The [SavedAgendaViewModel] reads it on entry.
 *
 * This exists because [SavedAgendaCreate][com.singularity.todo.feature.nav.AgendaStartRoute.SavedAgendaCreate]
 * is a data object route — it cannot carry parameters. The seed must be passed
 * through a side channel.
 *
 * The store is cleared after the [SavedAgendaViewModel] reads it (first read wins).
 */
class SavedAgendaSeedStore {
    private val _seed = MutableStateFlow<AgendaDefinition?>(null)
    val seed: StateFlow<AgendaDefinition?> = _seed.asStateFlow()

    /**
     * Write [definition] as the seed for the next created saved view.
     * Overwrites any previous seed that has not been consumed.
     */
    fun setSeed(definition: AgendaDefinition) {
        _seed.value = definition
    }

    /**
     * Read and clear the seed. Returns null if no seed was set.
     * Subsequent calls return null until [setSeed] is called again.
     */
    fun consumeSeed(): AgendaDefinition? {
        val current = _seed.value
        _seed.value = null
        return current
    }
}
