package com.singularity.todo.feature.agenda.domain.port

import com.singularity.todo.feature.agenda.domain.model.DateGrouping
import kotlinx.coroutines.flow.Flow

/**
 * The profile's chosen grouping for the Upcoming feed.
 *
 * One setting per profile: the settings file is shared by every profile on the
 * device, so the implementation keys the value by the profile-scoped user id.
 */
interface DateGroupingSettingsRepository {
    val grouping: Flow<DateGrouping>

    suspend fun setGrouping(grouping: DateGrouping)
}
