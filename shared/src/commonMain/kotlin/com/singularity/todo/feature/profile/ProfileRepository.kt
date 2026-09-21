package com.singularity.todo.feature.profile

import com.singularity.todo.core.repository.GenericUserScopedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for profile CRUD and active-profile selection.
 *
 * Profile records live in Room (durable, syncable), while the active-profile
 * key lives in DataStore (survives DB resets and wipes).
 */
interface ProfileRepository : GenericUserScopedRepository<Profile, ProfileId> {

    /** Watch the currently active profile. Emits the default if none selected. */
    fun activeProfile(): Flow<Profile>

    /** The active profile ID as a StateFlow for cheap reads. */
    val activeProfileId: StateFlow<ProfileId>

    /** Switch the active profile. Not part of generic CRUD. */
    suspend fun switchTo(id: ProfileId): Result<Unit>

    /**
     * Idempotent first-run seed. If the profiles table is empty, inserts the
     * default 'Personal' profile (and on CLI/MCP hosts also the 'AI Agent'
     * profile used by `--profile=ai-agent`). Safe to call repeatedly.
     */
    suspend fun ensureDefaults(extraProfiles: List<Triple<String, String, Int>> = emptyList())
}
