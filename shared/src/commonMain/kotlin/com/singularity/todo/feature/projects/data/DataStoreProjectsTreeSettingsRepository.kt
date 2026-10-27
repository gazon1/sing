package com.singularity.todo.feature.projects.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsNamespace
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsTreeSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Production [ProjectsTreeSettingsRepository] backed by DataStore.
 *
 * ## Why the key carries the user
 *
 * The settings file is one file for the whole app, and the profiles share it —
 * which is why the key carries the profile-scoped user id. Without it, the
 * arrangement of one profile's tree would be the arrangement the other profile
 * sees, and the folding a user made for their work would follow them into
 * their personal one. The scoped id (`"{profileId}/{userId}"`) is the app's own
 * per-profile identity, so the key needs no second notion of "profile".
 */
class DataStoreProjectsTreeSettingsRepository(
    dataStore: DataStore<Preferences>,
    private val userId: Flow<UserId>,
) : BaseSettingsRepository(dataStore), ProjectsTreeSettingsRepository {

    /** The preference of one profile. */
    private fun collapsedPref(profileUserId: UserId) =
        stringSetPref(nsKey(SettingsNamespace.PROJECTS, "collapsed.${profileUserId.value}"))

    override val collapsedProjectIds: Flow<Set<ProjectId>> =
        userId.flatMapLatest { profileUserId ->
            collapsedPref(profileUserId).flow.map { stored ->
                stored.map { ProjectId.fromString(it) }.toSet()
            }
        }

    override suspend fun toggleCollapsed(projectId: ProjectId) {
        val pref = collapsedPref(userId.first())
        pref.set(
            pref.flow.first().let { current ->
                if (projectId.value in current) current - projectId.value else current + projectId.value
            },
        )
    }
}
