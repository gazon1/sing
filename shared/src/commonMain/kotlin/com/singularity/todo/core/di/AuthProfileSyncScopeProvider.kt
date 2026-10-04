package com.singularity.todo.core.di

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncScopeProvider
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The `(owner, profile)` pair sync currently operates on, derived from the signed-in
 * session and the active profile.
 *
 * `null` while signed out, or before a profile exists — both are states in which there
 * is nothing to synchronise, and both must be distinguishable from a scope that simply
 * has not synced yet (which is a real scope with a cursor of zero).
 *
 * Lives here rather than in `core/sync` because answering it requires knowing about the
 * profile feature, and `core` does not depend on `feature`. The alternative — passing
 * the two ids in from the caller — would put the "which profile am I?" question in
 * every sync entry point, which is how they drift apart.
 *
 * @see SyncScope for why the pair is the unit rather than the account alone.
 */
internal class AuthProfileSyncScopeProvider(authRepository: AuthRepository, profileRepository: ProfileRepository) :
    SyncScopeProvider {

    override val current: Flow<SyncScope?> =
        combine(authRepository.currentSession, profileRepository.activeProfileId) { session, profileId ->
            val ownerId = (session as? Session.SignedIn)?.userId?.value
            ownerId?.let { SyncScope(ownerId = it, profileId = profileId.value) }
        }
}
