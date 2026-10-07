package com.singularity.todo.core.ui.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Whether the user has finished the current version of the spotlight tour.
 *
 * Reuses the settings store that already exists rather than introducing a second
 * mechanism for "seen a hint". A separate store would need its own migration, its own
 * behaviour when settings are cleared, and its own answer to sign-out — three things
 * this store has already decided.
 */
interface OnboardingSettingsRepository {

    /** Highest tour version the user has finished. `0` means they have not. */
    val seenSpotlightVersion: Flow<Int>

    /** Records [version] as seen. Monotonic: a lower version is ignored. */
    suspend fun markSpotlightSeen(version: Int)

    /**
     * Clears the record so the tour runs again.
     *
     * An explicit action rather than "set it back to 0", because a user asking to see
     * the hints again wants them now, not on some future first run.
     */
    suspend fun resetSpotlight()
}

/** [OnboardingSettingsRepository] backed by DataStore. */
class DataStoreOnboardingSettingsRepository(dataStore: DataStore<Preferences>) :
    BaseSettingsRepository(dataStore),
    OnboardingSettingsRepository {

    private val seenVersionPref =
        intPref(nsKey(SettingsNamespace.ONBOARDING, "spotlight_seen_version"), NEVER_SEEN)

    override val seenSpotlightVersion: Flow<Int> = seenVersionPref.flow

    override suspend fun markSpotlightSeen(version: Int) {
        // Monotonic on purpose. Writing a lower version would re-show the tour to a user
        // who has already finished a newer one, which happens the moment two devices
        // disagree about what the current version is.
        if (version <= seenVersionPref.flow.first()) return
        seenVersionPref.set(version)
    }

    override suspend fun resetSpotlight() {
        seenVersionPref.set(NEVER_SEEN)
    }

    private companion object {
        const val NEVER_SEEN = 0
    }
}
