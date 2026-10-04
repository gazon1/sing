package com.singularity.todo.feature.profile

import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
    private fun computeScopedUserId(userId: UserId, profileId: ProfileId): UserId =
        if (profileId == ProfileId.default) {
            userId
        } else {
            UserId.fromString("${profileId.value}/${userId.value}")
        }

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
     *
     * ## It lags a switch by up to one dispatch
     *
     * That collector is asynchronous, so immediately after a profile or user switch
     * this `StateFlow` still reports the **previous** identity. Two properties, both
     * deliberate and both with a correct alternative:
     *
     * - **imperative reads** (`.value`, [current]) — right for a one-shot write, which
     *   must pick exactly one identity at the moment of writing. The window is one
     *   dispatch, so a write driven by a UI action cannot land in it;
     * - **anything that must be correct across a switch** — a *subscription*
     *   ([observeForCurrentUser]) or a read that must describe the *pre-switch*
     *   identity ([liveLocalUserId]). Using [scopedUserId] for those is a race.
     *
     * See ADR `2026-10-04-derived-identity-flows`.
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

    /**
     * Derived scoped id that can never lag the upstream pair.
     *
     * [scopedUserId] is a `StateFlow`, so it must be *seeded* with a value and can only
     * be corrected later by the async collector in [init] — and its own input,
     * [CurrentUser.userId], has the same shape. Collecting it immediately after a sign-in
     * or a profile switch therefore observes the **previous** identity, which for a
     * user-scoped repository read means the old user's rows, or none at all.
     *
     * [CurrentUser.liveUserId] is derived from the session itself, so combining it with
     * [profileId] yields the identity that is true *at collection time*.
     *
     * Prefer this for every reactive read ([com.singularity.todo.core.repository.observeForCurrentUser]);
     * [scopedUserId] stays for imperative reads, where an eager seed is exactly what you want.
     */
    val liveScopedUserId: Flow<UserId> =
        combine(currentUser.liveUserId, profileRepository.activeProfileId) { userId, profileId ->
            computeScopedUserId(userId, profileId)
        }

    /**
     * The **un-scoped** userId, derived from the session.
     *
     * Distinct from [liveScopedUserId] on purpose: this one does not move when the
     * profile changes, which is exactly what a caller needs when it must reason about
     * the identity *before* a switch — "move these rows from the local user to the
     * agent scope", for example. Reading the cached [scopedUserId] for that purpose is
     * a race: the switch gives the collector in [init] a chance to run, and the read
     * may then return an already-scoped id and build a target id of
     * `"{profile}/{profile}/{user}"`, matching nothing and leaving rows unreachable.
     */
    val liveLocalUserId: Flow<UserId> = currentUser.liveUserId

    /**
     * Convenience for imperative reads — a one-shot write picks one identity here.
     *
     * Lags a switch by up to one dispatch, same as [scopedUserId]; see its KDoc for
     * which reads are safe and which must use [liveScopedUserId] / [liveLocalUserId].
     */
    val current: UserId get() = scopedUserId.value
}
