package com.singularity.todo.feature.plans.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsNamespace
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.plans.domain.port.PlansSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Production [PlansSettingsRepository] backed by DataStore.
 *
 * The value is stored as the enum's name, so a build that learns a new
 * granularity can still read what an older one wrote: an unknown name falls
 * back to the day rather than leaving the screen without a grouping.
 *
 * ## Why the key carries the user
 *
 * The settings file is one file for the whole app, and the profiles share it —
 * which is why the key carries the profile-scoped user id. Without it, the
 * granularity a profile chose would be the granularity the other profile sees,
 * and the choice the user made for their work profile would follow them into
 * their personal one. The scoped id (`"{profileId}/{userId}"`) is the app's
 * own per-profile identity, so the key needs no second notion of "profile".
 */
class DataStorePlansSettingsRepository(dataStore: DataStore<Preferences>, private val userId: Flow<UserId>) :
    BaseSettingsRepository(dataStore),
    PlansSettingsRepository {

    /** The preference of one profile. */
    private fun granularityPref(profileUserId: UserId) =
        enumPref(
            nsKey(SettingsNamespace.PLANS, "granularity.${profileUserId.value}"),
            PlansGranularity.Default,
            PlansGranularity.entries,
        )

    override val granularity: Flow<PlansGranularity> =
        userId.flatMapLatest { profileUserId ->
            granularityPref(profileUserId).flow.map { PlansGranularity.fromStorage(it.name) }
        }

    override suspend fun setGranularity(granularity: PlansGranularity) {
        granularityPref(userId.first()).set(granularity)
    }
}
