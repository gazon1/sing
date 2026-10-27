package com.singularity.todo.feature.plans.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.plans.data.DataStorePlansSettingsRepository
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
        val repository = DataStorePlansSettingsRepository(inMemoryDataStore(), flowOf(TestUsers.DEFAULT))

        repository.setGranularity(PlansGranularity.Week)

        assertEquals(PlansGranularity.Week, repository.granularity.first())
    }

    @Test
    fun `a profile that chose nothing gets the day`() = runTest {
        val repository = DataStorePlansSettingsRepository(inMemoryDataStore(), flowOf(TestUsers.DEFAULT))

        assertEquals(PlansGranularity.Day, repository.granularity.first())
    }

    @Test
    fun `a stored value this build cannot read yields the day`() = runTest {
        val store = inMemoryDataStore()
        val repository = DataStorePlansSettingsRepository(store, flowOf(TestUsers.DEFAULT))

        // A value written by a build that knew a granularity this one does not.
        store.edit { it[stringPreferencesKey("plans.granularity.${TestUsers.DEFAULT.value}")] = "Year" }

        assertEquals(PlansGranularity.Day, repository.granularity.first())
    }

    @Test
    fun `two profiles keep their own granularity`() = runTest {
        val store = inMemoryDataStore()
        val userId = MutableStateFlow(TestUsers.DEFAULT)
        val repository = DataStorePlansSettingsRepository(store, userId)

        repository.setGranularity(PlansGranularity.Month)
        assertEquals(PlansGranularity.Month, repository.granularity.first())

        // The other profile has chosen nothing, so it gets the day.
        userId.value = UserId("work/user_1")
        assertEquals(PlansGranularity.Day, repository.granularity.first())

        // And its own choice does not touch the first profile's.
        repository.setGranularity(PlansGranularity.Week)
        assertEquals(PlansGranularity.Week, repository.granularity.first())

        userId.value = TestUsers.DEFAULT
        assertEquals(PlansGranularity.Month, repository.granularity.first())
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
