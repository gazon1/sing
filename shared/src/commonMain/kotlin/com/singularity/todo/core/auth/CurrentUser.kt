package com.singularity.todo.core.auth

import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
class CurrentUser(
    authRepository: AuthRepository,
    private val scope: CoroutineScope,
) {
    val userId: StateFlow<UserId> = authRepository.session
        .map { AuthDomain.effectiveUserId(it) }
        .stateIn(scope, SharingStarted.Eagerly, UserId.anonymous)

    /** Convenience for synchronous reads in imperative code paths. */
    val current: UserId get() = userId.value
}
