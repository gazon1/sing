package com.singularity.todo.feature.profile

import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Wraps [CurrentUser] and adds per-profile isolation.
 *
 * In the future, each profile will have its own auth session. For now, the
 * profileId is prepended to the userId to form a compound identity:
 * `"{profileId.value}/{userId.value}"`. This keeps existing data visible while
 * allowing the MCP server to route requests to the correct profile.
 *
 * Example: profile "abc", userId "user_1" → "abc/user_1"
 */
class ProfileAwareCurrentUser(
      currentUser: CurrentUser,
      profileRepository: ProfileRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Profile-scoped userId: `"{profileId}/{userId}"` or just `userId`
     * when the profile is the default one (backwards-compatible).
     */
    val scopedUserId: StateFlow<UserId> = combine(
        currentUser.userId,
        profileRepository.activeProfileId,
    ) { userId, profileId ->
        if (profileId == ProfileId.default) {
            userId
        } else {
            UserId.fromString("${profileId.value}/${userId.value}")
        }
    }.stateIn(scope, SharingStarted.Eagerly, UserId.anonymous)

    /** The raw (un-scoped) userId from the auth session. */
    val userId: StateFlow<UserId> = currentUser.userId

    /** The currently active profile ID. */
    val profileId: StateFlow<ProfileId> = profileRepository.activeProfileId

    /** Convenience for imperative reads. */
    val current: UserId get() = scopedUserId.value
}
