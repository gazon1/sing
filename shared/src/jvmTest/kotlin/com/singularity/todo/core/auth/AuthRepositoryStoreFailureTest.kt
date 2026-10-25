@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.auth

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.test.helpers.awaitState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REQ-UA-011 — a store failure is a failure, not an exception.
 *
 * ## Why these cases are one file
 *
 * They share one thing: the credential store refuses the session the provider just
 * issued. Everything else about them differs — a sign-in, a sign-up, the message the
 * user reads, the compensating sign-out at the provider — and each of those is a
 * separate promise. What ties them together is the shape of the failure, so they
 * live together and are read together.
 *
 * ## Why the write is the provider's
 *
 * The provider owns the session now: the plugin saves it through the app's
 * credential store, inside the gateway call, before the repository ever hears about
 * it. A write that fails is therefore a failure of the attempt — and the attempt is
 * what the repository reports.
 */
@Tag("fast")
class AuthRepositoryStoreFailureTest {

    private val secure = MapSecureStorage()
    private val preferences = newPreferencesStore()

    private val legacy = DataStoreSessionStore(preferences, FixedIdGenerator("dev"))

    private val store = SecureSessionManager(
        log = testLogger(),
        secure = secure,
        legacy = legacy,
    )
    private val gateway = ScriptedGateway(store)

    private fun TestScope.repository() = SupabaseAuthRepository(
        log = testLogger(),
        gateway = gateway,
        localOwnerStore = legacy,
        scope = AutoCloseableCoroutineScope(coroutineContext),
    )

    private fun signedIn(email: String = "a@b.c") =
        RemoteSession("owner-1", email, "access-1", "refresh-1", isAnonymous = false)

    /**
     * A repository whose credential store refuses every write.
     *
     * The store is the one the gateway writes through, so the refusal happens
     * inside the attempt — which is the whole point of these cases.
     */
    private fun TestScope.repositoryWithFailingStore(): Pair<ScriptedGateway, SupabaseAuthRepository> {
        val failingStore = SecureSessionManager(
            log = testLogger(),
            secure = MapSecureStorage(failWriteNumber = 1),
            legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
        )
        val failingGateway = ScriptedGateway(failingStore)
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = failingGateway,
            localOwnerStore = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
        return failingGateway to repository
    }

    @Test
    fun `a store that cannot take the session makes sign-in a failure, not a throw`() = runTest {
        // The case the test plan found. Writing the session was the last statement of
        // a success handler, and a handler that throws is not caught by the result it
        // is attached to — so this escaped the repository entirely and, one layer up,
        // crashed the view model's scope with nothing on screen.
        val (gateway, repository) = repositoryWithFailingStore()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        val result = repository.signIn("a@b.c", "password123")

        assertTrue(result.isFailure, "a sign-in that could not be stored did not happen")
        assertEquals(SecureSessionManager.STORAGE_FAILED, assertIs<AppError>(result.exceptionOrNull()).code)
    }

    @Test
    fun `a store that cannot take the session makes sign-up a failure, not a throw`() = runTest {
        // The write is the provider's now: the plugin saves the session it issued
        // through the credential store, inside the gateway call. One write, so the
        // first one is the one that fails.
        val (gateway, repository) = repositoryWithFailingStore()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signUpResult = Result.success(signedIn())

        val result = repository.signUp("a@b.c", "password123")

        assertTrue(result.isFailure)
        assertEquals(SecureSessionManager.STORAGE_FAILED, assertIs<AppError>(result.exceptionOrNull()).code)
    }

    @Test
    fun `the session the device could not store is ended at the provider`() = runTest {
        // Otherwise the provider keeps a live session for an account the user believes
        // failed, and nothing revokes it: the refresh token never reached the device,
        // so no later request carries it.
        val (gateway, repository) = repositoryWithFailingStore()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        repository.signIn("a@b.c", "password123")

        assertEquals(1, gateway.signOutCalls, "the orphaned session is still alive at the provider")
    }

    @Test
    fun `a store failure does not end a session the device never had one for`() = runTest {
        // Guards the guard: the compensating sign-out is for a session that was issued
        // and lost, not a blanket "sign out whenever something failed".
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.failure(AppError.Unauthorized("no such account"))

        repository.signIn("a@b.c", "password123")

        assertEquals(0, gateway.signOutCalls)
    }

    @Test
    fun `the store failure message does not blame the credentials`() = runTest {
        val (gateway, repository) = repositoryWithFailingStore()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signUpResult = Result.success(signedIn())

        val message = assertIs<AppError>(repository.signUp("a@b.c", "password123").exceptionOrNull()).message
        val lower = message.orEmpty().lowercase()

        assertFalse(
            lower.contains("password") && lower.contains("wrong"),
            "the password was accepted; a user told otherwise will retype it: $message",
        )
        assertFalse(lower.contains("invalid credential"), message)
    }

    @Test
    fun `the store failure message says the attempt got as far as the provider`() = runTest {
        // For a sign-up the account now exists, and a blind retry answers "that address
        // is already registered" — so the user has to know the account is real.
        val (gateway, repository) = repositoryWithFailingStore()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signUpResult = Result.success(signedIn())

        val message = assertIs<AppError>(repository.signUp("a@b.c", "password123").exceptionOrNull())
            .message.orEmpty()

        assertTrue(
            message.contains("account", ignoreCase = true),
            "the account exists even though the session could not be stored: $message",
        )
    }

    @Test
    fun `the pending flag is cleared after a store failure`() = runTest {
        val (gateway, repository) = repositoryWithFailingStore()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        repository.signIn("a@b.c", "password123")

        assertFalse(repository.isLoading.value, "a spinner that never stops is how a user files this as a hang")
    }

    @Test
    fun `a store failure leaves the device signed out rather than half signed in`() = runTest {
        val (gateway, repository) = repositoryWithFailingStore()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        repository.signIn("a@b.c", "password123")

        assertIs<Session.SignedOut>(repository.currentSession.value)
        assertNull(
            gateway.currentSession(),
            "a session that could not be stored is not left behind",
        )
    }
}
