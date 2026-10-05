@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.auth

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId
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
 * The authentication repository, driven through a gateway that can be told to
 * fail, to answer with a different identity, or to reject a refresh.
 *
 * ## Why the gateway fake is so accommodating
 *
 * Every case here is one the happy path cannot produce: a provider that accepts a
 * request and leaves no session, a refresh the server refuses, an attach that
 * changes the owner. They are exactly the cases a stub returning
 * `Result.success` forever would have hidden, and each of them ends in the user
 * believing something false — a spinner that never stops, an account they cannot
 * get back into, or data that has quietly stopped belonging to them.
 */
@Tag("fast")
class AuthRepositoryTest {

    private val gateway = ScriptedGateway()
    private val secure = MapSecureStorage()
    private val preferences = newPreferencesStore()

    private val store = SecureSessionStore(
        log = testLogger(),
        secure = secure,
        legacy = DataStoreSessionStore(preferences, FixedIdGenerator("dev")),
    )

    private fun TestScope.repository() = SupabaseAuthRepository(
        log = testLogger(),
        gateway = gateway,
        sessionStore = store,
        scope = AutoCloseableCoroutineScope(coroutineContext),
    )

    /** A store already holding a signed-in session, for the restore paths. */
    private suspend fun TestScope.repositoryWithStoredSession(
        access: String = "access-1",
        refresh: String = "refresh-1",
        userId: String = "owner-1",
    ): SupabaseAuthRepository {
        store.save(Session.SignedIn(UserId.fromString(userId), "a@b.c", access, refresh))
        return repository()
    }

    // ── REQ-UA-001 — the four verbs ─────────────────────────────────────────

    @Test
    fun `signing in publishes the session and stores the tokens`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        assertTrue(repository.signIn("a@b.c", "password123").isSuccess)

        val session = assertIs<Session.SignedIn>(repository.currentSession.value)
        assertEquals(UserId.fromString("owner-1"), session.userId)
        assertEquals("a@b.c", session.email)
        assertEquals("access-1", secure.values[SecureSessionStore.KEY_ACCESS])
        assertEquals("refresh-1", secure.values[SecureSessionStore.KEY_REFRESH])
        assertEquals("owner-1", secure.values[SecureSessionStore.KEY_USER_ID])
    }

    @Test
    fun `signing up publishes the session`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signUpResult = Result.success(signedIn(email = "new@b.c"))

        assertTrue(repository.signUp("new@b.c", "password123").isSuccess)

        assertEquals("new@b.c", assertIs<Session.SignedIn>(repository.currentSession.value).email)
    }

    @Test
    fun `an anonymous session is a real identity and leaves no tokens behind`() = runTest {
        // REQ-UA-004. A session with no credentials must not leave tokens: the next
        // launch would find an access token with no refresh token and try to refresh
        // it, which is a signed-out user reported as an error.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.anonymousResult = Result.success(anonymous())

        assertTrue(repository.signInAnonymously().isSuccess)

        assertEquals(UserId.fromString("anon-1"), assertIs<Session.Anonymous>(repository.currentSession.value).userId)
        assertNull(secure.values[SecureSessionStore.KEY_ACCESS])
    }

    @Test
    fun `an invalid address is rejected before the provider is asked`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        val result = repository.signIn("not-an-address", "password123")

        assertTrue(result.isFailure)
        assertEquals(0, gateway.signInCalls, "validation must not become a network request")
    }

    @Test
    fun `a sign-up awaiting email confirmation succeeds without a session`() = runTest {
        // The account was created, so reporting failure would send the user round
        // again and have them meet "that address is already registered". The truth
        // is the pair: success, and a session that is signed out — which is what
        // tells the screen to say "check your mail".
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signUpResult = Result.success(RemoteSession("owner-9", null, "a", "r", isAnonymous = false))

        assertTrue(repository.signUp("a@b.c", "password123").isSuccess)
        assertEquals(Session.SignedOut, repository.currentSession.value)
        assertNull(
            secure.values[SecureSessionStore.KEY_ACCESS],
            "there is no token to persist until the address is confirmed",
        )
    }

    // ── REQ-UA-007 — the pending flag ───────────────────────────────────────

    @Test
    fun `the pending flag clears when the attempt fails`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.failure(AppError.Unauthorized("bad password"))

        repository.signIn("a@b.c", "password123")

        assertFalse(repository.isLoading.value, "a spinner that outlives its operation is a hang")
    }

    @Test
    fun `the pending flag clears when the attempt succeeds`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        repository.signIn("a@b.c", "password123")

        assertFalse(repository.isLoading.value)
    }

    @Test
    fun `the pending flag clears when validation fails`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        repository.signIn("nope", "short")

        assertFalse(repository.isLoading.value)
    }

    @Test
    fun `the pending flag clears when signing out fails`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())
        repository.signIn("a@b.c", "password123")
        gateway.signOutResult = Result.failure(AppError.Network("offline"))

        repository.signOut()

        assertFalse(repository.isLoading.value)
    }

    // ── REQ-UA-006 — sign out ───────────────────────────────────────────────

    @Test
    fun `signing out clears the credentials even when the server is unreachable`() = runTest {
        // The user asked to be signed out. Whether the server agreed is a separate
        // question, and refusing to sign out locally because a request failed would
        // mean they cannot sign out on a plane.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())
        repository.signIn("a@b.c", "password123")
        gateway.signOutResult = Result.failure(AppError.Network("offline"))

        repository.signOut()

        assertEquals(Session.SignedOut, repository.currentSession.value)
        assertNull(secure.values[SecureSessionStore.KEY_ACCESS])
        assertNull(secure.values[SecureSessionStore.KEY_REFRESH])
        assertNull(secure.values[SecureSessionStore.KEY_USER_ID])
    }

    @Test
    fun `signing out leaves the device id alone`() = runTest {
        // The device id is how the server tells installs apart, not a credential.
        // Clearing it on sign-out would make every sign-out look like a new device
        // to the event log.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        val deviceId = store.getOrInitDeviceId()

        repository.signOut()

        assertEquals(deviceId, store.getOrInitDeviceId())
    }

    @Test
    fun `signing out sends the sign-out to the provider`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())
        repository.signIn("a@b.c", "password123")

        repository.signOut()

        assertEquals(1, gateway.signOutCalls)
    }

    // ── REQ-UA-003 — restoring a session ────────────────────────────────────

    @Test
    fun `a session the server refuses to refresh signs the user out`() = runTest {
        // null is the gateway's answer for "the refresh token is dead", which is a
        // different thing from "the request failed" and the only reason the
        // repository can tell recoverable from not.
        gateway.refreshResult = Result.success(null)
        val repository = repositoryWithStoredSession()
        awaitState { repository.currentSession.value !is Session.Loading }

        assertEquals(Session.SignedOut, repository.currentSession.value)
        assertNull(store.currentAccessToken(), "a token the server refused is not worth keeping")
    }

    @Test
    fun `a refresh that fails on the network keeps the stored session`() = runTest {
        gateway.signInResult = Result.success(signedIn())
        val first = repository()
        awaitState { first.currentSession.value !is Session.Loading }
        first.signIn("a@b.c", "password123")

        gateway.refreshResult = Result.failure(AppError.Network("offline"))
        val restored = repositoryWithStoredSession()
        awaitState { restored.currentSession.value !is Session.Loading }

        assertEquals(
            UserId.fromString("owner-1"),
            assertIs<Session.SignedIn>(restored.currentSession.value).userId,
            "an unreachable server says nothing about whether the token is valid",
        )
        assertEquals("access-1", store.currentAccessToken())
    }

    @Test
    fun `a session the server accepts is stored with its new tokens`() = runTest {
        gateway.refreshResult = Result.success(
            RemoteSession("owner-1", "a@b.c", "access-2", "refresh-2", isAnonymous = false),
        )

        val repository = repositoryWithStoredSession()
        awaitState { repository.currentSession.value !is Session.Loading }

        assertEquals("access-2", store.currentAccessToken())
        assertEquals("access-2", assertIs<Session.SignedIn>(repository.currentSession.value).accessToken)
    }

    @Test
    fun `a device with nothing stored starts signed out`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        assertEquals(Session.SignedOut, repository.currentSession.value)
    }

    // ── REQ-UA-005 — attaching an account to an anonymous session ────────────

    @Test
    fun `attaching an account keeps the owner, so existing data stays reachable`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.anonymousResult = Result.success(anonymous())
        repository.signInAnonymously()
        gateway.attachResult = Result.success(RemoteSession("anon-1", "new@b.c", "a2", "r2", isAnonymous = false))

        assertTrue(repository.migrateAnonymousTo("new@b.c", "password123").isSuccess)

        val session = assertIs<Session.SignedIn>(repository.currentSession.value)
        assertEquals(
            UserId.fromString("anon-1"),
            session.userId,
            "the owner is unchanged, so every row already written under it is this account's",
        )
    }

    @Test
    fun `an attach that changes the owner is reported, not adopted`() = runTest {
        // The one case the "nothing moves" argument does not cover. A provider that
        // issued a different id would orphan every row: local data under the old
        // owner, server data under the new one, and no error from either side.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.anonymousResult = Result.success(anonymous())
        repository.signInAnonymously()
        gateway.attachResult = Result.success(RemoteSession("anon-2", "new@b.c", "a2", "r2", isAnonymous = false))

        val result = repository.migrateAnonymousTo("new@b.c", "password123")

        assertEquals("auth.ownership_changed", assertIs<AppError>(result.exceptionOrNull()).code)
    }

    @Test
    fun `attaching to a signed-in session is refused before the provider is asked`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())
        repository.signIn("a@b.c", "password123")

        val result = repository.migrateAnonymousTo("new@b.c", "password123")

        assertEquals("auth.not_anonymous", assertIs<AppError>(result.exceptionOrNull()).code)
        assertEquals(0, gateway.attachCalls)
    }

    @Test
    fun `attaching with an invalid address never reaches the provider`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.anonymousResult = Result.success(anonymous())
        repository.signInAnonymously()

        assertTrue(repository.migrateAnonymousTo("nope", "short").isFailure)
        assertEquals(0, gateway.attachCalls)
    }

    // ── REQ-UA-009, REQ-UA-010 — the address the provider is asked about ────

    @Test
    fun `a padded capitalised address reaches the provider folded and trimmed`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        val result = repository.signIn("  User@Mail.COM  ", "password123")

        assertTrue(result.isSuccess, "an address that names one mailbox must not be refused as malformed")
        assertEquals("user@mail.com", gateway.lastSignInEmail)
    }

    @Test
    fun `the same address arrives at sign-up and at attach, folded the same way`() = runTest {
        // Three entry points, one normalisation: a fold applied to only some of them
        // is how a user gets "already registered" from one screen and a fresh account
        // from another.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        gateway.signUpResult = Result.success(signedIn())
        repository.signUp(" Signup@Mail.COM ", "password123")
        assertEquals("signup@mail.com", gateway.lastSignUpEmail)

        gateway.anonymousResult = Result.success(anonymous())
        repository.signInAnonymously()
        gateway.attachResult = Result.success(RemoteSession("anon-1", "a@b.c", "a1", "r1", isAnonymous = false))
        repository.migrateAnonymousTo(" Attach@Mail.COM ", "password123")
        assertEquals("attach@mail.com", gateway.lastAttachEmail)
    }

    @Test
    fun `an over-long address is refused without the provider being asked`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        val result = repository.signIn("a".repeat(300) + "@mail.com", "password123")

        assertEquals("auth.email.too_long", assertIs<AppError>(result.exceptionOrNull()).code)
        assertEquals(0, gateway.signInCalls)
    }

    @Test
    fun `an over-long password is refused without the provider being asked`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        val result = repository.signIn("a@b.c", "p".repeat(100))

        assertEquals("auth.password.too_long", assertIs<AppError>(result.exceptionOrNull()).code)
        assertEquals(0, gateway.signInCalls)
    }

    // ── REQ-UA-011 — a store failure is a failure, not an exception ─────────

    @Test
    fun `a store that cannot take the session makes sign-in a failure, not a throw`() = runTest {
        // The case the test plan found. Writing the session was the last statement of
        // a success handler, and a handler that throws is not caught by the result it
        // is attached to — so this escaped the repository entirely and, one layer up,
        // crashed the view model's scope with nothing on screen.
        val secure = MapSecureStorage(failWriteNumber = 1)
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            sessionStore = SecureSessionStore(
                log = testLogger(),
                secure = secure,
                legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
            ),
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        val result = repository.signIn("a@b.c", "password123")

        assertTrue(result.isFailure, "a sign-in that could not be stored did not happen")
        assertEquals(SecureSessionStore.STORAGE_FAILED, assertIs<AppError>(result.exceptionOrNull()).code)
    }

    @Test
    fun `a store that cannot take the session makes sign-up a failure, not a throw`() = runTest {
        val secure = MapSecureStorage(failWriteNumber = 2)
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            sessionStore = SecureSessionStore(
                log = testLogger(),
                secure = secure,
                legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
            ),
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signUpResult = Result.success(signedIn())

        val result = repository.signUp("a@b.c", "password123")

        assertTrue(result.isFailure)
        assertEquals(SecureSessionStore.STORAGE_FAILED, assertIs<AppError>(result.exceptionOrNull()).code)
    }

    @Test
    fun `the session the device could not store is ended at the provider`() = runTest {
        // Otherwise the provider keeps a live session for an account the user believes
        // failed, and nothing revokes it: the refresh token never reached the device,
        // so no later request carries it.
        val secure = MapSecureStorage(failWriteNumber = 1)
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            sessionStore = SecureSessionStore(
                log = testLogger(),
                secure = secure,
                legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
            ),
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
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
        val secure = MapSecureStorage(failWriteNumber = 1)
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            sessionStore = SecureSessionStore(
                log = testLogger(),
                secure = secure,
                legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
            ),
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
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
        val secure = MapSecureStorage(failWriteNumber = 1)
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            sessionStore = SecureSessionStore(
                log = testLogger(),
                secure = secure,
                legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
            ),
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
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
        val secure = MapSecureStorage(failWriteNumber = 1)
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            sessionStore = SecureSessionStore(
                log = testLogger(),
                secure = secure,
                legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
            ),
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        repository.signIn("a@b.c", "password123")

        assertFalse(repository.isLoading.value, "a spinner that never stops is how a user files this as a hang")
    }

    @Test
    fun `a store failure leaves the device signed out rather than half signed in`() = runTest {
        val secure = MapSecureStorage(failWriteNumber = 3)
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            sessionStore = SecureSessionStore(
                log = testLogger(),
                secure = secure,
                legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
            ),
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signInResult = Result.success(signedIn())

        repository.signIn("a@b.c", "password123")

        assertIs<Session.SignedOut>(repository.currentSession.value)
        assertNull(secure.values[SecureSessionStore.KEY_ACCESS])
    }

    // ── Infrastructure ──────────────────────────────────────────────────────

    private fun signedIn(email: String = "a@b.c") =
        RemoteSession("owner-1", email, "access-1", "refresh-1", isAnonymous = false)

    private fun anonymous() = RemoteSession("anon-1", null, "access-a", "refresh-a", isAnonymous = true)
}

/** A gateway whose every answer is set by the test. */
private class ScriptedGateway : AuthGateway {
    var signInResult: Result<RemoteSession> = Result.failure(AppError.Unauthorized("not scripted"))
    var signUpResult: Result<RemoteSession> = signInResult
    var anonymousResult: Result<RemoteSession> = signInResult
    var attachResult: Result<RemoteSession> = signInResult
    var signOutResult: Result<Unit> = Result.success(Unit)
    var refreshResult: Result<RemoteSession?> = Result.success(null)

    var signInCalls = 0
    var attachCalls = 0
    var signOutCalls = 0

    /** The address the repository actually sent, which is not the one it was given. */
    var lastSignUpEmail: String? = null
    var lastSignInEmail: String? = null
    var lastAttachEmail: String? = null

    override suspend fun signUp(email: String, password: String): Result<RemoteSession> {
        lastSignUpEmail = email
        return signUpResult
    }

    override suspend fun signIn(email: String, password: String): Result<RemoteSession> {
        signInCalls++
        lastSignInEmail = email
        return signInResult
    }

    override suspend fun signInAnonymously(): Result<RemoteSession> = anonymousResult

    override suspend fun attachEmail(email: String, password: String): Result<RemoteSession> {
        attachCalls++
        lastAttachEmail = email
        return attachResult
    }

    override suspend fun signOut(): Result<Unit> {
        signOutCalls++
        return signOutResult
    }

    override suspend fun refresh(accessToken: String, refreshToken: String): Result<RemoteSession?> = refreshResult
}
