@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.auth

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.helpers.awaitState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    private val secure = MapSecureStorage()
    private val preferences = newPreferencesStore()

    private val legacy = DataStoreSessionStore(preferences, FixedIdGenerator("dev"))

    /**
     * The credential store, and the provider that owns it.
     *
     * The store is behind the provider now: the plugin saves and loads the session
     * through it, and the repository never reaches into it. The fake provider below
     * does the same, so these tests are about a world in which the session is really
     * persisted.
     */
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
        val stored = assertNotNull(store.currentSession(), "the provider keeps the session it issued")
        assertEquals("access-1", stored.accessToken)
        assertEquals("refresh-1", stored.refreshToken)
        assertEquals(UserId.fromString("owner-1"), stored.userId)
    }

    @Test
    fun `signing up publishes the session`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        gateway.signUpResult = Result.success(signedIn(email = "new@b.c"))

        assertTrue(repository.signUp("new@b.c", "password123").isSuccess)

        assertEquals("new@b.c", assertIs<Session.SignedIn>(repository.currentSession.value).email)
    }

    // ── REQ-UA-004 — the local session ────────────────────────────────────────

    @Test
    fun `the local session contacts no provider at all`() = runTest {
        // The whole point. This method used to be `gateway.signInAnonymously()`, so a
        // device with no project — or no network, which is the entire reason the button
        // exists — got an error and stayed on the auth screen.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        assertTrue(repository.startLocalSession().isSuccess)

        assertEquals(0, gateway.signInCalls)
        assertEquals(0, gateway.signUpResultCalls)
    }

    @Test
    fun `the local session is a local identity and leaves no tokens behind`() = runTest {
        // A session with no credentials must not leave tokens: the next launch would
        // find an access token with no refresh token and try to refresh it, which is a
        // signed-out user reported as an error.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        assertTrue(repository.startLocalSession().isSuccess)

        val session = assertIs<Session.Anonymous>(repository.currentSession.value)
        assertTrue(
            session.userId.value.startsWith(DataStoreSessionStore.LOCAL_OWNER_PREFIX),
            "a local owner is namespaced so it can never collide with a provider id, was ${session.userId.value}",
        )
        assertNull(store.currentSession(), "a local session leaves no credential behind")
    }

    @Test
    fun `the local session survives the next launch`() = runTest {
        // The bug this whole path exists to remove: the auth screen reappearing over an
        // app the user had already been let into, on every cold start.
        val first = repository()
        awaitState { first.currentSession.value !is Session.Loading }
        assertTrue(first.startLocalSession().isSuccess)
        val owner = assertIs<Session.Anonymous>(first.currentSession.value).userId

        val second = repository()
        awaitState { second.currentSession.value !is Session.Loading }

        assertEquals(owner, assertIs<Session.Anonymous>(second.currentSession.value).userId)
    }

    @Test
    fun `a stored token outranks the local marker`() = runTest {
        // Both can be on disk at once: an account signed in, then a sign-out that
        // cleared the marker but whose token write failed. The token is the real
        // session, and resuming local instead would show the user their empty local
        // data while holding valid credentials for an account.
        // Seeded before the second repository exists: its `init` launches the restore,
        // so a value written afterwards would race the very branch under test.
        store.save(Session.SignedIn(UserId.fromString("owner-1"), "a@b.c", "access-1", "refresh-1"))
        legacy.markLocalSessionActive()
        gateway.refreshResult = Result.success(signedIn())

        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        assertIs<Session.SignedIn>(repository.currentSession.value)
    }

    @Test
    fun `signing out forgets the offline mode but keeps the local owner id`() = runTest {
        // The id is the only thing that can still find rows created offline. Clearing
        // it would orphan every task the user made without an account, with no error,
        // and the next local session would generate a different one.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        assertTrue(repository.startLocalSession().isSuccess)
        val owner = assertIs<Session.Anonymous>(repository.currentSession.value).userId

        assertTrue(repository.signOut().isSuccess)

        assertFalse(legacy.isLocalSessionActive())
        assertEquals(owner, UserId.fromString(legacy.getOrInitLocalOwnerId()))
        assertIs<Session.SignedOut>(repository.currentSession.value)
    }

    @Test
    fun `continuing offline again after a sign-out lands on the same owner`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        assertTrue(repository.startLocalSession().isSuccess)
        val owner = assertIs<Session.Anonymous>(repository.currentSession.value).userId
        repository.signOut()

        assertTrue(repository.startLocalSession().isSuccess)

        assertEquals(owner, assertIs<Session.Anonymous>(repository.currentSession.value).userId)
    }

    @Test
    fun `a fresh install with no marker shows the login screen`() = runTest {
        // The other half of "sign out keeps the id": without a marker there is nothing
        // to resume, and a user who has never chosen offline must still be asked.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        assertIs<Session.SignedOut>(repository.currentSession.value)
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
            store.currentSession(),
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
        assertNull(store.currentSession(), "the credentials are gone even though the server was unreachable")
    }

    @Test
    fun `signing out leaves the device id alone`() = runTest {
        // The device id is how the server tells installs apart, not a credential.
        // Clearing it on sign-out would make every sign-out look like a new device
        // to the event log.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        val deviceId = legacy.getOrInitDeviceId()

        repository.signOut()

        assertEquals(deviceId, legacy.getOrInitDeviceId())
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
        assertNull(store.currentSession()?.accessToken, "a token the server refused is not worth keeping")
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
        assertEquals("access-1", store.currentSession()?.accessToken)
    }

    @Test
    fun `a session the server accepts is stored with its new tokens`() = runTest {
        gateway.refreshResult = Result.success(
            RemoteSession("owner-1", "a@b.c", "access-2", "refresh-2", isAnonymous = false),
        )

        val repository = repositoryWithStoredSession()
        awaitState { repository.currentSession.value !is Session.Loading }

        assertEquals("access-2", store.currentSession()?.accessToken)
        assertEquals("access-2", assertIs<Session.SignedIn>(repository.currentSession.value).accessToken)
    }

    // ── the session the provider replaces ─────────────────────────────────────

    @Test
    fun `a session the provider replaces is published with the same identity`() = runTest {
        gateway.signInResult = Result.success(signedIn())
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        repository.signIn("a@b.c", "password123")

        // The provider rotated the tokens behind the application's back.
        gateway.publishSession(
            RemoteSession("owner-1", "a@b.c", "access-2", "refresh-2", isAnonymous = false),
        )

        awaitState {
            val current = repository.currentSession.value
            current is Session.SignedIn && current.accessToken == "access-2"
        }
        val session = assertIs<Session.SignedIn>(repository.currentSession.value)
        assertEquals("access-2", session.accessToken, "the tokens the provider holds are the ones the app hands out")
        assertEquals(UserId.fromString("owner-1"), session.userId, "the identity does not change")
    }

    @Test
    fun `a session the provider reports for another account is not published`() = runTest {
        gateway.signInResult = Result.success(signedIn())
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        repository.signIn("a@b.c", "password123")

        gateway.publishSession(
            RemoteSession("owner-2", "other@b.c", "access-other", "refresh-other", isAnonymous = false),
        )
        advanceUntilIdle()

        val session = assertIs<Session.SignedIn>(repository.currentSession.value)
        assertEquals("access-1", session.accessToken, "another account's session is not this device's to publish")
        assertEquals(UserId.fromString("owner-1"), session.userId)
    }

    @Test
    fun `a provider that holds no session does not sign the user out`() = runTest {
        gateway.signInResult = Result.success(signedIn())
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        repository.signIn("a@b.c", "password123")

        gateway.publishSession(signedIn())
        advanceUntilIdle()
        gateway.publishSession(null)
        advanceUntilIdle()

        assertIs<Session.SignedIn>(repository.currentSession.value)
        assertEquals("access-1", assertIs<Session.SignedIn>(repository.currentSession.value).accessToken)
    }

    @Test
    fun `a device with nothing stored starts signed out`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        assertEquals(Session.SignedOut, repository.currentSession.value)
    }

    // ── REQ-UA-015 — a store that cannot answer, and one that cannot be read ─
    //
    // Every case above is a store that works and a server that misbehaves. These four
    // are the ones that were missing, and missing them is why a real device spent a
    // release on an unfinishable launch: the fakes built for this suite all answer in
    // microseconds, so "the store never answers" had no way to be written down, let
    // alone asserted on.
    //
    // Hanging and throwing look the same from the outside and are not the same bug.
    // A hang is a timeout; a throw is not — `withTimeoutOrNull` returns null only for
    // `TimeoutCancellationException` and passes everything else straight through. So
    // each pair here needs its own test: fixing one path leaves the other broken, and
    // the UI cannot tell the difference.

    /** The restore budget is 10 s of virtual time; `awaitState` needs more than that. */
    private fun TestScope.awaitRestoreSettled(repository: SupabaseAuthRepository) = awaitState(
        timeoutMs = RESTORE_BUDGET_PLUS_MARGIN_MS,
    ) { repository.currentSession.value !is Session.Loading }

    @Test
    fun `a secure store that never answers still leaves the loading session`() = runTest {
        val hanging = HangingSecureStorage()
        val frozen = DataStoreSessionStore(hangingPreferencesStore(), FixedIdGenerator("dev"))
        val gateway = ScriptedGateway(
            SecureSessionManager(log = testLogger(), secure = hanging, legacy = frozen),
        )
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            localOwnerStore = frozen,
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )

        awaitRestoreSettled(repository)

        assertEquals(
            Session.SignedOut,
            repository.currentSession.value,
            "an unresponsive store leaves nothing to restore, so the login screen is the honest answer",
        )
    }

    @Test
    fun `a secure store that cannot be read still leaves the loading session`() = runTest {
        // The uninstall/reinstall case: Auto Backup brings the ciphertext back, the
        // Android Keystore key that decrypts it does not exist, and the first read
        // raises a security failure for the rest of the install's life.
        val gateway = ScriptedGateway(
            SecureSessionManager(
                log = testLogger(),
                secure = ThrowingSecureStorage { SecurityException("no such key") },
                legacy = legacy,
            ),
        )
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            localOwnerStore = legacy,
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )

        awaitRestoreSettled(repository)

        assertEquals(Session.SignedOut, repository.currentSession.value)
    }

    @Test
    fun `a local owner store that never answers still leaves the loading session`() = runTest {
        // The other store on the first-launch path. With nothing stored, deciding
        // between "offline" and "signed out" is a read of exactly this store, so a
        // store that hangs here is the one a fresh install actually meets.
        val frozen = DataStoreSessionStore(hangingPreferencesStore(), FixedIdGenerator("dev"))
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            localOwnerStore = frozen,
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )

        awaitRestoreSettled(repository)

        assertEquals(Session.SignedOut, repository.currentSession.value)
    }

    @Test
    fun `a local owner store that cannot be read still leaves the loading session`() = runTest {
        // SecurityException, not IOException: `catchDataStoreIoError` catches
        // IOException and substitutes empty preferences, so an IOException fake would
        // exercise the branch that already works and pass for the wrong reason.
        val broken = DataStoreSessionStore(
            throwingPreferencesStore { SecurityException("corrupt") },
            FixedIdGenerator("dev"),
        )
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            localOwnerStore = broken,
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )

        awaitRestoreSettled(repository)

        assertEquals(Session.SignedOut, repository.currentSession.value)
    }

    @Test
    fun `a store that keeps throwing makes a sign-in a failure, not a crash`() = runTest {
        // The same store, still broken, reached through a different door: a write
        // rather than a read. `attempt` captures the store write, so an unwritable
        // store ends the attempt as a failure the screen can report.
        //
        // What this does NOT cover is the recovery itself — `AndroidSecureStorage`
        // erasing a file it cannot decrypt lives in androidMain behind a `Context`,
        // so proving that the next launch starts clean needs an instrumented test,
        // not a JVM one. What is provable here is the half that is: the failure
        // stops at the result boundary instead of leaving the repository.
        val gateway = ScriptedGateway(
            SecureSessionManager(
                log = testLogger(),
                secure = ThrowingSecureStorage { SecurityException("no such key") },
                legacy = legacy,
            ),
        )
        gateway.signInResult = Result.success(signedIn())
        val repository = SupabaseAuthRepository(
            log = testLogger(),
            gateway = gateway,
            localOwnerStore = legacy,
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )

        awaitRestoreSettled(repository)
        val outcome = repository.signIn("a@b.c", "password123")

        assertTrue(outcome.isFailure, "an unwritable store cannot complete a sign-in")
        assertTrue(
            repository.currentSession.value is Session.SignedOut,
            "a failed attempt must not leave the session looking signed in",
        )
    }

    // ── REQ-UA-005 — attaching an account ────────────────────────────────────
    //
    // The repository's half of attaching is an ordinary sign-in: the credentials are
    // validated and the provider is asked, and the owner that comes back is whatever
    // identity the account has always had. What happens to the local rows afterwards is
    // `LocalAccountClaimer`'s job — it needs the database collaborators this repository
    // deliberately does not hold. What is tested here is that the two halves join up
    // without a gap.

    @Test
    fun `attaching an account is a sign-in, and the local session is no longer live`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        assertTrue(repository.startLocalSession().isSuccess)
        val local = assertIs<Session.Anonymous>(repository.currentSession.value).userId
        gateway.signInResult = Result.success(signedIn())

        assertTrue(repository.signIn("new@b.c", "password123").isSuccess)

        val session = assertIs<Session.SignedIn>(repository.currentSession.value)
        assertEquals(UserId.fromString("owner-1"), session.userId)
        assertNotEquals(
            local,
            session.userId,
            "the account's own id differs from the local one — which is exactly why the rows " +
                "have to be moved rather than left where they are",
        )
    }

    @Test
    fun `a wrong password on attach leaves the local session exactly as it was`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        assertTrue(repository.startLocalSession().isSuccess)
        val local = assertIs<Session.Anonymous>(repository.currentSession.value).userId
        gateway.signInResult = Result.failure(AppError.Unauthorized("invalid credentials"))

        assertTrue(repository.signIn("new@b.c", "wrong-password").isFailure)

        assertEquals(local, assertIs<Session.Anonymous>(repository.currentSession.value).userId)
    }

    @Test
    fun `attaching with an invalid address never reaches the provider`() = runTest {
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }
        assertTrue(repository.startLocalSession().isSuccess)

        assertTrue(repository.signIn("nope", "short").isFailure)
        assertEquals(0, gateway.signInCalls)
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
    fun `the same address arrives at sign-up and at sign-in, folded the same way`() = runTest {
        // Two entry points, one normalisation: a fold applied to only one of them is
        // how a user gets "already registered" from one screen and a fresh account from
        // another.
        val repository = repository()
        awaitState { repository.currentSession.value !is Session.Loading }

        gateway.signUpResult = Result.success(signedIn())
        repository.signUp(" Signup@Mail.COM ", "password123")
        assertEquals("signup@mail.com", gateway.lastSignUpEmail)

        gateway.signInResult = Result.success(signedIn())
        repository.signIn(" Signin@Mail.COM ", "password123")
        assertEquals("signin@mail.com", gateway.lastSignInEmail)
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

    // ── Magic link ────────────────────────────────────────────────────────────

    @Test
    fun `verifyMagicLink succeeds and creates a signed-in session`() = runTest {
        val repo = repository()
        awaitState { repo.currentSession.value !is Session.Loading }
        gateway.verifyMagicLinkResult = Result.success(signedIn("a@b.c"))

        val result = repo.verifyMagicLink("a@b.c", "the-token")

        assertTrue(result.isSuccess)
        val session = repo.currentSession.value
        assertIs<Session.SignedIn>(session)
        assertEquals("a@b.c", session.email)
    }

    @Test
    fun `verifyMagicLink failure propagates the error without changing session`() = runTest {
        val repo = repository()
        awaitState { repo.currentSession.value !is Session.Loading }
        gateway.verifyMagicLinkResult = Result.failure(AppError.Unauthorized("bad token"))

        val result = repo.verifyMagicLink("a@b.c", "bad-token")

        assertTrue(result.isFailure)
        assertIs<Session.SignedOut>(repo.currentSession.value)
    }

    @Test
    fun `sendMagicLink succeeds without changing session`() = runTest {
        val repo = repository()
        awaitState { repo.currentSession.value !is Session.Loading }

        val result = repo.sendMagicLink("a@b.c")

        assertTrue(result.isSuccess)
        assertIs<Session.SignedOut>(repo.currentSession.value)
        assertEquals(1, gateway.sendMagicLinkCalls)
    }

    @Test
    fun `sendMagicLink failure propagates the error without changing session`() = runTest {
        val repo = repository()
        awaitState { repo.currentSession.value !is Session.Loading }
        gateway.sendMagicLinkResult = Result.failure(AppError.Network("unreachable"))

        val result = repo.sendMagicLink("a@b.c")

        assertTrue(result.isFailure)
        assertIs<Session.SignedOut>(repo.currentSession.value)
    }

    // ── Infrastructure ──────────────────────────────────────────────────────

    private fun signedIn(email: String = "a@b.c") =
        RemoteSession("owner-1", email, "access-1", "refresh-1", isAnonymous = false)
}

/**
 * A gateway whose every answer is set by the test.
 *
 * ## Why it holds the credential store
 *
 * The provider owns the session now: the plugin saves and loads it through the app's
 * credential store, and the repository never reaches into the store. A fake that did
 * not keep the session would be a fake in a world where signing in never persists
 * anything — and the tests below are about what the device keeps.
 */
internal class ScriptedGateway(private val store: SecureSessionManager) : AuthGateway {
    var signInResult: Result<RemoteSession> = Result.failure(AppError.Unauthorized("not scripted"))
    var signUpResult: Result<RemoteSession> = signInResult
    var signOutResult: Result<Unit> = Result.success(Unit)
    var refreshResult: Result<RemoteSession?> = Result.success(null)
    var sendMagicLinkResult: Result<Unit> = Result.success(Unit)
    var verifyMagicLinkResult: Result<RemoteSession> = Result.success(
        RemoteSession("dev-user-id", "a@b.c", "dev-access-token", "dev-refresh-token", false),
    )

    var signInCalls = 0
    var signUpResultCalls = 0
    var signOutCalls = 0
    var sendMagicLinkCalls = 0
    var verifyMagicLinkCalls = 0

    /** The address the repository actually sent, which is not the one it was given. */
    var lastSignUpEmail: String? = null
    var lastSignInEmail: String? = null
    var lastMagicLinkEmail: String? = null

    override suspend fun signUp(email: String, password: String): Result<RemoteSession> {
        signUpResultCalls++
        lastSignUpEmail = email
        val result = signUpResult
        if (result.isFailure) return result
        return save(result.getOrThrow())
    }

    override suspend fun signIn(email: String, password: String): Result<RemoteSession> {
        signInCalls++
        lastSignInEmail = email
        val result = signInResult
        if (result.isFailure) return result
        return save(result.getOrThrow())
    }

    override suspend fun signOut(): Result<Unit> {
        signOutCalls++
        // The plugin clears the session it holds, in the app's credential store,
        // whether or not the server agreed to end it there.
        runCatchingCancellable { store.deleteSession() }
        return signOutResult
    }

    override suspend fun refresh(accessToken: String, refreshToken: String): Result<RemoteSession?> {
        val result = refreshResult
        if (result.isFailure || result.getOrNull() == null) return result
        return persist(result)
    }

    /**
     * A refresh that was rejected leaves nothing behind either: the provider has
     * said the token is dead, and the session it belonged to is over.
     */
    private suspend fun persist(result: Result<RemoteSession?>): Result<RemoteSession?> {
        if (result.isFailure) return result
        val session = result.getOrNull() ?: return result
        return save(session)
    }

    override suspend fun currentSession(): RemoteSession? {
        val session = store.currentSession() ?: return null
        return RemoteSession(
            userId = session.userId.value,
            email = session.email,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            isAnonymous = false,
        )
    }

    /**
     * Saves the session the provider issued, the way the plugin does.
     *
     * A store that cannot take it turns the attempt into a failure — and the failure
     * has to reach the repository as a result, not as an exception: a handler that
     * throws is not caught by the result it is attached to.
     *
     * A session with no address is the shape a sign-up that needs its address
     * confirmed comes back in, and there is no session behind it to keep: the
     * provider issued no session, so nothing is written.
     */
    private suspend fun save(session: RemoteSession): Result<RemoteSession> {
        if (session.email.isNullOrBlank() && !session.isAnonymous) {
            return Result.success(session)
        }
        return runCatchingCancellable {
            store.save(
                Session.SignedIn(
                    userId = UserId.fromString(session.userId),
                    email = session.email.orEmpty(),
                    accessToken = session.accessToken,
                    refreshToken = session.refreshToken,
                ),
            )
            session
        }
    }

    override suspend fun updatePassword(newPassword: String): Result<Unit> = Result.success(Unit)

    override suspend fun sendMagicLink(email: String): Result<Unit> {
        sendMagicLinkCalls++
        lastMagicLinkEmail = email
        return sendMagicLinkResult
    }

    override suspend fun verifyMagicLink(email: String, token: String): Result<RemoteSession> {
        verifyMagicLinkCalls++
        return verifyMagicLinkResult
    }

    private val sessionChanges = MutableStateFlow<RemoteSession?>(null)

    /**
     * The provider's own answer about the session, as it changes.
     *
     * A test that needs the provider to replace a session behind the repository's
     * back says so here — that is the only way the repository learns about a session
     * it did not ask for.
     */
    fun publishSession(remote: RemoteSession?) {
        sessionChanges.value = remote
    }

    override fun sessionChanges(): Flow<RemoteSession?> = sessionChanges
}

/**
 * How long a REQ-UA-015 test waits for the session to settle.
 *
 * `SupabaseAuthRepository` gives session restore a 10-second budget. A test that
 * waits less would time out on its own deadline and report the symptom — "the
 * session never left Loading" — for a session that was about to leave it, which is
 * the most misleading failure this file can produce. The margin is what separates
 * "still restoring" from "restoring gave up".
 */
private const val RESTORE_BUDGET_PLUS_MARGIN_MS = 15_000L
