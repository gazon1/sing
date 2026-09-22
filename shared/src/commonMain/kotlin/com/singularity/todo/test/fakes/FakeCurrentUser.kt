package com.singularity.todo.test.fakes

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.createBackgroundScope
import kotlinx.coroutines.CoroutineScope

/**
 * Builds a [CurrentUser] backed by a [FakeAuthRepository] for tests.
 *
 * Usage:
 * ```kotlin
 * val authRepo = FakeAuthRepository(Session.Anonymous(UserId.fromString("u1")))
 * val currentUser = FakeCurrentUser(authRepo, scope = backgroundScope)
 * val vm = TaskDetailViewModel(deps, taskId, currentUser)
 * ```
 *
 * @param scope CoroutineScope for hosting the userId StateFlow's collector.
 *   Pass `backgroundScope` in jvmTest (auto-cancelled at teardown). Pass
 *   `createBackgroundScope()` in commonTest direct constructor calls —
 *   safe only when the consumer reads `.value` synchronously and never
 *   subscribes to the StateFlow.
 */
fun FakeCurrentUser(
    authRepository: AuthRepository = FakeAuthRepository(),
    scope: CoroutineScope = createBackgroundScope(),
): CurrentUser = CurrentUser(authRepository, scope)

/** Variant that starts the session in [SignedOut][Session.SignedOut] for guard tests. */
fun FakeCurrentUserSignedOut(
    scope: CoroutineScope = createBackgroundScope(),
): CurrentUser = CurrentUser(FakeAuthRepository(initialSession = Session.SignedOut), scope)
