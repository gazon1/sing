package com.singularity.todo.feature.agenda.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsNamespace
import com.singularity.todo.feature.agenda.domain.model.DateGrouping
import com.singularity.todo.feature.agenda.domain.port.DateGroupingSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Production [DateGroupingSettingsRepository] backed by DataStore.
 *
 * The value is stored as the enum's name, so a build that learns a new grouping
 * can still read what an older one wrote: an unknown name falls back to the day
 * rather than leaving the tab without a grouping.
 *
 * The key carries the profile-scoped user id because the settings file is one
 * file for the whole app and the profiles share it. Without it, a grouping chosen
 * in the work profile would follow the user into the personal one.
 */
class DataStoreDateGroupingSettingsRepository(dataStore: DataStore<Preferences>, private val userId: Flow<UserId>) :
    BaseSettingsRepository(dataStore),
    DateGroupingSettingsRepository {
    private fun groupingPref(profileUserId: UserId) =
        enumPref(
            nsKey(SettingsNamespace.AGENDA, "upcoming_grouping.${profileUserId.value}"),
            DateGrouping.Default,
            DateGrouping.entries,
        )

    override val grouping: Flow<DateGrouping> =
        userId.flatMapLatest { profileUserId -> groupingPref(profileUserId).flow }

    override suspend fun setGrouping(grouping: DateGrouping) {
        groupingPref(userId.first()).set(grouping)
    }
}
