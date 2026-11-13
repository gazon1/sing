package com.singularity.todo.feature.projects.presentation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A preferences store that answers from memory, and can be told to fail or to
 * take its time.
 *
 * The point of the delay and the failure is the contract around the recently
 * used emoji: picking an icon must not wait for the history to be written. A
 * store that always answers at once cannot show that, and one that never fails
 * cannot show that a failing write leaves the pick alone.
 */
class FakePreferencesDataStore(
    initial: Preferences = emptyPreferences(),
    private val onUpdate: suspend (Preferences) -> Preferences = { it },
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = onUpdate(updated)
        return state.value
    }
}
