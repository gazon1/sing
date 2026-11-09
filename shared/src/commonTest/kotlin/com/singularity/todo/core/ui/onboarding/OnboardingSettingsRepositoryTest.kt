package com.singularity.todo.core.ui.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What "show tips again" writes, and what it announces.
 *
 * Clearing the stored version is what makes the tour eligible after a relaunch; the
 * announcement is what makes it appear now, on a screen that stays composed in the
 * navigation stack. Both halves matter, and each covers the case the other cannot: an
 * announcement nobody is listening to is dropped, and the cleared version is then what
 * the next screen reads.
 */
@Tag("fast")
class OnboardingSettingsRepositoryTest {
    @Test
    fun `resetting the tour clears the record and announces a replay`() =
        runTest {
            val repository = DataStoreOnboardingSettingsRepository(InMemoryPreferencesStore())
            repository.markSpotlightSeen(SpotlightContent.VERSION)

            val replays = mutableListOf<Unit>()
            // Cancelled with the test scope; the collector only has to be subscribed
            // before the reset for the emission to be observed.
            backgroundScope.launch {
                repository.spotlightReplayRequests.collect { replays += it }
            }
            // The flow has no replay buffer: a collector that has not subscribed yet
            // misses the emission, which is the behaviour the fallback relies on.
            runCurrent()

            repository.resetSpotlight()
            runCurrent()

            assertEquals(0, repository.seenSpotlightVersion.first())
            assertEquals(1, replays.size, "a live screen must be told to start the tour again")
        }

    @Test
    fun `a reset with nothing listening still leaves the tour eligible`() =
        runTest {
            // The screen that hosts the tour is not always composed when the settings row
            // is pressed. The cleared version is what it reads when it next composes.
            val repository = DataStoreOnboardingSettingsRepository(InMemoryPreferencesStore())
            repository.markSpotlightSeen(SpotlightContent.VERSION)

            repository.resetSpotlight()

            assertEquals(
                0,
                repository.seenSpotlightVersion.first(),
                "the next screen to compose must still show the tour",
            )
        }

    @Test
    fun `completing the tour records the version it was showing`() =
        runTest {
            val repository = DataStoreOnboardingSettingsRepository(InMemoryPreferencesStore())

            repository.markSpotlightSeen(SpotlightContent.VERSION)

            assertEquals(SpotlightContent.VERSION, repository.seenSpotlightVersion.first())
        }

    /**
     * The smallest [DataStore] the repository needs.
     *
     * A preference store is a flow plus an update, so a file and a serializer are not
     * needed to answer the question these tests ask — what the repository writes and
     * announces, not how DataStore persists it.
     */
    private class InMemoryPreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
