package com.singularity.todo.feature.plans.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsNamespace
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.plans.domain.port.PlansSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Production [PlansSettingsRepository] backed by DataStore.
 *
 * The value is stored as the enum's name, so a build that learns a new
 * granularity can still read what an older one wrote: an unknown name falls
 * back to the day rather than leaving the screen without a grouping.
 */
class DataStorePlansSettingsRepository(dataStore: DataStore<Preferences>) :
    BaseSettingsRepository(dataStore),
    PlansSettingsRepository {

    private val granularityPref = enumPref(
        nsKey(SettingsNamespace.PLANS, "granularity"),
        PlansGranularity.Default,
        PlansGranularity.entries,
    )

    override val granularity: Flow<PlansGranularity> =
        granularityPref.flow.map { PlansGranularity.fromStorage(it.name) }

    override suspend fun setGranularity(granularity: PlansGranularity) {
        granularityPref.set(granularity)
    }
}
