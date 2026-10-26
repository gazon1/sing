package com.singularity.todo.feature.plans.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.feature.plans.data.DataStorePlansSettingsRepository
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The granularity's round-trip through the store.
 *
 * The choice has to survive leaving the screen, so it is written to the
 * profile's settings and read back — and a store that cannot be read must still
 * leave the screen with a grouping rather than with nothing.
 */
@Tag("fast")
class PlansSettingsRepositoryTest {

    @Test
    fun `the chosen granularity is read back`() = runTest {
        val repository = DataStorePlansSettingsRepository(inMemoryDataStore())

        repository.setGranularity(PlansGranularity.Week)

        assertEquals(PlansGranularity.Week, repository.granularity.first())
    }

    @Test
    fun `a profile that chose nothing gets the day`() = runTest {
        val repository = DataStorePlansSettingsRepository(inMemoryDataStore())

        assertEquals(PlansGranularity.Day, repository.granularity.first())
    }

    @Test
    fun `a stored value this build cannot read yields the day`() = runTest {
        val store = inMemoryDataStore()
        val repository = DataStorePlansSettingsRepository(store)

        // A value written by a build that knew a granularity this one does not.
        store.edit { it[stringPreferencesKey("plans.granularity")] = "Year" }

        assertEquals(PlansGranularity.Day, repository.granularity.first())
    }

    /** A [DataStore] held in memory, so the test touches no files. */
    private fun inMemoryDataStore(
        initial: Preferences = emptyPreferences(),
    ): DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        return object : DataStore<Preferences> {
            override val data: Flow<Preferences> = state
            override suspend fun updateData(
                transform: suspend (Preferences) -> Preferences,
            ): Preferences = transform(state.value).also { state.value = it }
        }
    }
}
