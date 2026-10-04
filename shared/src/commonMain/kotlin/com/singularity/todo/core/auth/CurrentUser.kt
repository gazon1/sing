package com.singularity.todo.core.auth

import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Reactive, single source of truth for the current userId.
 *
 * Replaces the scattered `settingsRepository.userId.first()` / hardcoded
 * `UserId.anonymous` reads across ViewModels. Subscribers receive the latest
 * value via `StateFlow.value` and react to auth transitions (signed-in →
 * anonymous → signed-out) without each VM having to re-derive the user.
 *
 * Lifetime: process-wide via Koin `single`. The flow is collected eagerly so
 * the value is ready by the time the first ViewModel subscribes.
 *
 * @param scope CoroutineScope for hosting the userId StateFlow's collector.
 *   Mandatory — caller is responsible for providing the scope. In production
 *   this comes from Koin's `single { ... createBackgroundScope() }`. In tests,
 *   inject a `TestScope` or `backgroundScope`.
 */
class CurrentUser(authRepository: AuthRepository, private val scope: CoroutineScope) {
    private val _userId = MutableStateFlow(UserId.anonymous)
    val userId: StateFlow<UserId> = _userId

    /**
     * The userId derived straight from the session, with no cached intermediate.
     *
     * [userId] is a `StateFlow` seeded with [UserId.anonymous] and corrected by the
     * collector below, so for a short window after sign-in it still reports
     * `"anonymous"` even though the session already carries a real id. A consumer that
     * reads it at that moment — and a second consumer that reads it a moment later —
     * can disagree, and the disagreement is a race with the collector, not with the
     * session. That is not hypothetical: it made the profile-isolation tests in
     * `ReadToolsProfileAwareTest` fail on the Android/Robolectric source set only,
     * because the collector landed between the test's seeding read and the tool's read.
     *
     * Prefer this for any reactive or cross-component read; [userId] stays for
     * imperative reads, where an eagerly seeded value is exactly what you want.
     */
    val liveUserId: Flow<UserId> = authRepository.currentSession
        .map { AuthDomain.effectiveUserId(it) }
        .distinctUntilChanged()

    init {
        scope.launch {
            liveUserId.collect { _userId.value = it }
        }
    }

    /** Convenience for synchronous reads in imperative code paths. */
    val current: UserId get() = userId.value
}
