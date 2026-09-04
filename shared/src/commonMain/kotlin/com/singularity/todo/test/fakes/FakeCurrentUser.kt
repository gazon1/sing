package com.singularity.todo.test.fakes

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.auth.Session

/**
 * Builds a [CurrentUser] backed by a [FakeAuthRepository] for tests.
 *
 * Usage:
 * ```kotlin
 * val authRepo = FakeAuthRepository(Session.Anonymous(UserId.fromString("u1")))
 * val currentUser = FakeCurrentUser(authRepo)
 * val vm = TasksViewModel(..., currentUser = currentUser, ...)
 * ```
 */
fun FakeCurrentUser(
    authRepository: AuthRepository = FakeAuthRepository(),
): CurrentUser = CurrentUser(authRepository)

/** Variant that starts the session in [SignedOut][Session.SignedOut] for guard tests. */
fun FakeCurrentUserSignedOut(): CurrentUser =
    CurrentUser(FakeAuthRepository(initialSession = Session.SignedOut))
