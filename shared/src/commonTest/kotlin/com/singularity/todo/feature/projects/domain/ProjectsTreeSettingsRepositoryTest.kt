package com.singularity.todo.feature.projects.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.data.DataStoreProjectsTreeSettingsRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
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
 * The folding of the project tree, round-tripped through the store.
 *
 * The folding is the user's own arrangement of their work, so it has to survive
 * leaving the menu — and it has to stay the arrangement of the profile that made
 * it, because a work profile's tree and a personal one's are two arrangements.
 */
@Tag("fast")
class ProjectsTreeSettingsRepositoryTest {

    @Test
    fun `the folded project is read back`() = runTest {
        val repository = DataStoreProjectsTreeSettingsRepository(inMemoryDataStore(), flowOf(TestUsers.DEFAULT))

        repository.toggleCollapsed(ProjectId("p1"))

        assertEquals(setOf(ProjectId("p1")), repository.collapsedProjectIds.first())
    }

    @Test
    fun `a profile that folded nothing has no folded project`() = runTest {
        val repository = DataStoreProjectsTreeSettingsRepository(inMemoryDataStore(), flowOf(TestUsers.DEFAULT))

        assertEquals(emptySet(), repository.collapsedProjectIds.first())
    }

    @Test
    fun `toggling twice unfolds the project again`() = runTest {
        val repository = DataStoreProjectsTreeSettingsRepository(inMemoryDataStore(), flowOf(TestUsers.DEFAULT))

        repository.toggleCollapsed(ProjectId("p1"))
        repository.toggleCollapsed(ProjectId("p1"))

        assertEquals(emptySet(), repository.collapsedProjectIds.first())
    }

    @Test
    fun `two profiles keep their own folding`() = runTest {
        val store = inMemoryDataStore()
        val userId = MutableStateFlow(TestUsers.DEFAULT)
        val repository = DataStoreProjectsTreeSettingsRepository(store, userId)

        repository.toggleCollapsed(ProjectId("work"))
        assertEquals(setOf(ProjectId("work")), repository.collapsedProjectIds.first())

        // The other profile has folded nothing.
        userId.value = UserId("work/user_1")
        assertEquals(emptySet(), repository.collapsedProjectIds.first())

        // And its own folding does not touch the first profile's.
        repository.toggleCollapsed(ProjectId("personal"))
        assertEquals(setOf(ProjectId("personal")), repository.collapsedProjectIds.first())

        userId.value = TestUsers.DEFAULT
        assertEquals(setOf(ProjectId("work")), repository.collapsedProjectIds.first())
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
