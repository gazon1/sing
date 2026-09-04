package com.singularity.todo.core.auth

import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 */
class CurrentUser(authRepository: AuthRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val userId: StateFlow<UserId> = authRepository.session
        .map { AuthDomain.effectiveUserId(it) }
        .stateIn(scope, SharingStarted.Eagerly, UserId.anonymous)

    /** Convenience for synchronous reads in imperative code paths. */
    val current: UserId get() = userId.value
}
