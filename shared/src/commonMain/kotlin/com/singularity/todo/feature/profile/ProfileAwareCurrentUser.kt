package com.singularity.todo.feature.profile

import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Computes the profile-scoped userId for [profileId] from the raw auth [userId].
 *
 * Single source of truth for the profile → userId mapping. [ProfileAwareCurrentUser]
 * uses it for the active profile; the saved-view copy-to-profile flow uses it to
 * resolve the target profile's namespace. Exposed as a top-level function so the
 * two call sites cannot drift.
 */
fun scopedUserIdFor(profileId: ProfileId, userId: UserId): UserId = if (profileId == ProfileId.default) {
    userId
} else {
    UserId.fromString("${profileId.value}/${userId.value}")
}

/**
 * Wraps [CurrentUser] and adds per-profile isolation.
 *
 * In the future, each profile will have its own auth session. For now, the
 * profileId is prepended to the userId to form a compound identity:
 * `"{profileId.value}/{userId.value}"`. This keeps existing data visible while
 * allowing the MCP server to route requests to the correct profile.
 *
 * Example: profile "abc", userId "user_1" → "abc/user_1"
 *
 * @param scope CoroutineScope for hosting the scopedUserId StateFlow's collector.
 *   Mandatory — caller is responsible for providing the scope. In production
 *   this comes from Koin's `single { ... createBackgroundScope() }`. In tests,
 *   inject a `TestScope` or `backgroundScope`.
 */
open class ProfileAwareCurrentUser(
    currentUser: CurrentUser,
    profileRepository: ProfileRepository,
    private val scope: CoroutineScope,
) {

    /** Computes the scoped userId from the current upstreams — synchronous, no dispatch. */
    private fun computeScopedUserId(userId: UserId, profileId: ProfileId): UserId = scopedUserIdFor(profileId, userId)

    /**
     * Profile-scoped userId: `"{profileId}/{userId}"` or just `userId`
     * when the profile is the default one (backwards-compatible).
     *
     * ## Why seeded synchronously
     *
     * Both `currentUser.userId` and `profileRepository.activeProfileId` are
     * `StateFlow` already seeded before this constructor runs. Computing the
     * initial value directly (not via an async collector) means `scopedUserId.value`
     * is correct from the first read — no race with test-body code such as
     * `seedTask` that runs before the first `collect` emission.
     *
     * The `scope.launch { combine(...).collect {...} }` handles runtime user
     * switches and profile switches.
     */
    private val _scopedUserId = MutableStateFlow(
        computeScopedUserId(
            currentUser.userId.value,
            profileRepository.activeProfileId.value,
        ),
    )
    open val scopedUserId: StateFlow<UserId> = _scopedUserId

    init {
        scope.launch {
            combine(
                currentUser.userId,
                profileRepository.activeProfileId,
            ) { userId, profileId ->
                computeScopedUserId(userId, profileId)
            }.collect { _scopedUserId.value = it }
        }
    }

    /** The raw (un-scoped) userId from the auth session. */
    val userId: StateFlow<UserId> = currentUser.userId

    /** The currently active profile ID. */
    val profileId: StateFlow<ProfileId> = profileRepository.activeProfileId

    /** Convenience for imperative reads. */
    val current: UserId get() = scopedUserId.value
}
