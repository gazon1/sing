package com.singularity.todo.feature.agenda.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.model.DateGrouping
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Upcoming grouping's round-trip through the store.
 *
 * The choice has to survive leaving the tab, so it is written to the profile's
 * settings and read back. A store that cannot be read, or a value a newer build
 * wrote, must leave the tab with the day rather than with no grouping.
 */
@Tag("fast")
class DateGroupingSettingsRepositoryTest {
    @Test
    fun `the chosen grouping is read back`() =
        runTest {
            val repository = repository()

            repository.setGrouping(DateGrouping.Week)

            assertEquals(DateGrouping.Week, repository.grouping.first())
        }

    @Test
    fun `a profile that chose nothing gets the day`() =
        runTest {
            assertEquals(DateGrouping.Day, repository().grouping.first())
        }

    @Test
    fun `a stored value this build cannot read yields the day`() =
        runTest {
            val store = inMemoryDataStore()
            val repository = DataStoreDateGroupingSettingsRepository(store, flowOf(TestUsers.DEFAULT))

            // A value written by a build that knew a grouping this one does not.
            store.edit { it[stringPreferencesKey("agenda.upcoming_grouping.${TestUsers.DEFAULT.value}")] = "Year" }

            assertEquals(DateGrouping.Day, repository.grouping.first())
        }

    @Test
    fun `two profiles keep their own grouping`() =
        runTest {
            val store = inMemoryDataStore()
            val userId = MutableStateFlow(TestUsers.DEFAULT)
            val repository = DataStoreDateGroupingSettingsRepository(store, userId)

            repository.setGrouping(DateGrouping.Month)
            assertEquals(DateGrouping.Month, repository.grouping.first())

            userId.value = UserId("work/user_1")
            assertEquals(DateGrouping.Day, repository.grouping.first())

            repository.setGrouping(DateGrouping.Week)
            assertEquals(DateGrouping.Week, repository.grouping.first())

            userId.value = TestUsers.DEFAULT
            assertEquals(DateGrouping.Month, repository.grouping.first())
        }

    private fun repository() = DataStoreDateGroupingSettingsRepository(inMemoryDataStore(), flowOf(TestUsers.DEFAULT))

    /** A [DataStore] held in memory, so the test touches no files. */
    private fun inMemoryDataStore(initial: Preferences = emptyPreferences()): DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        return object : DataStore<Preferences> {
            override val data: Flow<Preferences> = state

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(state.value).also { state.value = it }
        }
    }
}
