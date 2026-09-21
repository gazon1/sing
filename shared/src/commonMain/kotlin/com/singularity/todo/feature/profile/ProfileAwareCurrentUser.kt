package com.singularity.todo.feature.profile

import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.CoroutineScope
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
 *
 * @param scope CoroutineScope for hosting the scopedUserId StateFlow's collector.
 *   Mandatory — caller is responsible for providing the scope. In production
 *   this comes from Koin's `single { ... createBackgroundScope() }`. In tests,
 *   inject a `TestScope` or `backgroundScope`.
 */
/**
 * Global accessor for the production [ProfileAwareCurrentUser] singleton.
 * Set during app startup via `ProfileAwareCurrentUser.instance = ...`.
 * AI tools and other non-DI-instantiated classes use this to access the
 * current userId without requiring constructor injection.
 */
/**
 * Global accessor for the production [ProfileAwareCurrentUser] singleton.
 * Set during app startup via `ProfileAwareCurrentUser.setInstance(...)`.
 * AI tools and other non-DI-instantiated classes use this to access the
 * current userId without requiring constructor injection.
 */
private var _currentUserInstance: ProfileAwareCurrentUser? = null

/**
 * DI injection point — call this from the app's root module to wire the global accessor.
 */
fun ProfileAwareCurrentUser.Companion.setInstance(instance: ProfileAwareCurrentUser) {
    _currentUserInstance = instance
}

open class ProfileAwareCurrentUser(
    currentUser: CurrentUser,
    profileRepository: ProfileRepository,
    private val scope: CoroutineScope,
) {

    /** Static singleton accessor for use in AI tools that don't receive ProfileAwareCurrentUser via DI. */
    companion object {
        private var _scopedUserId: StateFlow<UserId>? = null

        val scopedUserId: StateFlow<UserId>
            get() = _scopedUserId ?: _currentUserInstance?.scopedUserId
                ?: error("ProfileAwareCurrentUser.globalInstance not set. Call ProfileAwareCurrentUser.setInstance() in your app module.")

        val current: UserId
            get() = _currentUserInstance?.current
                ?: error("ProfileAwareCurrentUser.globalInstance not set. Call ProfileAwareCurrentUser.setInstance() in your app module.")

        /** Exposes the singleton for FakeTaskRepository default — resolves at access time. */
        internal val instance: ProfileAwareCurrentUser?
            get() = _currentUserInstance
    }

    /**
     * Profile-scoped userId: `"{profileId}/{userId}"` or just `userId`
     * when the profile is the default one (backwards-compatible).
     */
    open val scopedUserId: StateFlow<UserId> = combine(
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
