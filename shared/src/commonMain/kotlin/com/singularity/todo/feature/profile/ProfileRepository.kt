package com.singularity.todo.feature.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for profile CRUD and active-profile selection.
 *
 * Profile records live in Room (durable, syncable), while the active-profile
 * key lives in DataStore (survives DB resets and wipes).
 */
interface ProfileRepository {

    /** All profiles, ordered by createdAt. */
    fun all(): Flow<List<Profile>>

    /** Watch the currently active profile. Emits the default if none selected. */
    fun activeProfile(): Flow<Profile>

    /** The active profile ID as a StateFlow for cheap reads. */
    val activeProfileId: StateFlow<ProfileId>

    /** Create a new profile. Returns the new id. */
    suspend fun create(name: String, emoji: String, colorIdx: Int): ProfileId

    /** Rename and/or re-colour an existing profile. */
    suspend fun update(id: ProfileId, name: String, emoji: String, colorIdx: Int)

    /** Delete a profile. Fails if it is the last remaining profile. */
    suspend fun delete(id: ProfileId): Result<Unit>

    /** Switch the active profile. */
    suspend fun switchTo(id: ProfileId)

    /** Returns the Profile with [id], or null if not found. */
    suspend fun getById(id: ProfileId): Profile?
}
