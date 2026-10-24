package com.singularity.todo.feature.calendarSync.auth

import com.singularity.todo.core.auth.SecureStorage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Google sign-in, end to end except for the two things that leave the device.
 *
 * ## What is under test
 *
 * The consent step and the token exchange are the two halves this project owns, and
 * the failure this file guards against is the one that bit before: a sign-in that
 * *looked* wired and stored nothing. So every test here ends with an assertion about
 * what was persisted — the credential, the client id — and not merely about whether
 * the call returned.
 */
@Tag("fast")
class GoogleAuthorizationServiceTest {

    /** The narrow secure-store slice, in memory. */
    private class MapSecureStorage : SecureStorage {
        private val values = mutableMapOf<String, String>()

        override suspend fun read(key: String): String? = values[key]

        override suspend fun write(key: String, value: String) {
            values[key] = value
        }

        override suspend fun delete(key: String) {
            values.remove(key)
        }
    }

    /** Records what the exchange was asked to redeem. */
    private class FakeGrantRedeemer(
        private val result: Result<GoogleCredentials> = Result.success(
            GoogleCredentials("access", "refresh", 42L),
        ),
    ) : GoogleGrantRedeemer {
        var grant: GoogleGrant? = null
            private set

        var clientId: String? = null
            private set

        var codeVerifier: String? = null
            private set

        override suspend fun redeem(
            grant: GoogleGrant,
            clientId: String,
            codeVerifier: String?,
        ): Result<GoogleCredentials> {
            this.grant = grant
            this.clientId = clientId
            this.codeVerifier = codeVerifier
            return result
        }
    }

    /** Keeps what was saved, so a test can ask what reached the store. */
    private class RecordingCredentialStore : GoogleCredentialStore {
        val saved = mutableMapOf<String, GoogleCredentials>()

        override suspend fun load(userId: String): GoogleCredentials? = saved[userId]

        override suspend fun save(userId: String, credentials: GoogleCredentials) {
            saved[userId] = credentials
        }

        override suspend fun clear(userId: String) {
            saved.remove(userId)
        }
    }

    private fun service(
        storage: SecureStorage = MapSecureStorage(),
        redeemer: GoogleGrantRedeemer = FakeGrantRedeemer(),
        store: GoogleCredentialStore = RecordingCredentialStore(),
        buildTimeClientId: String? = "build-time-id",
    ): Triple<GoogleAuthorizationService, RecordingCredentialStore, FakeGrantRedeemer> {
        val recording = store as RecordingCredentialStore
        val token = redeemer as FakeGrantRedeemer
        val resolver = GoogleClientIdResolver(
            buildTime = { buildTimeClientId?.let { BuildTimeGoogleClientConfig(it) } },
        )
        val service = GoogleAuthorizationService(
            clientIdResolver = resolver,
            secureStorage = storage,
            redeemer = token,
            credentialStore = recording,
        )
        return Triple(service, recording, token)
    }

    /**
     * The sign-in has to *store* something.
     *
     * Before, the button was a no-op and the credential store stayed empty: the flow
     * ran, and the account was still disconnected. The assertion is on the store, not
     * on the returned value.
     */
    @Test
    fun `a redeemed grant is stored for the user`() = runTest {
        val (service, store, _) = service()
        val pending = service.beginAuthorization().getOrThrow()

        val result = service.redeem(
            pending = pending,
            grant = GoogleGrant.ServerAuthCode("the-code"),
            userId = "user-1",
        )

        assertTrue(result.isSuccess, "the sign-in failed: ${result.exceptionOrNull()}")
        assertNotNull(store.saved["user-1"], "the credential must reach the store")
        assertEquals("access", store.saved["user-1"]?.accessToken)
        assertEquals("refresh", store.saved["user-1"]?.refreshToken)
    }

    /**
     * The exchange presents what the consent produced.
     *
     * The client id is the one the request was made against, and the verifier is the
     * secret half of the PKCE pair — the exchange is rejected without it.
     */
    @Test
    fun `the exchange carries the grant, the client id and the verifier`() = runTest {
        val (service, _, redeemer) = service()
        val pending = service.beginAuthorization().getOrThrow()

        service.redeem(
            pending = pending,
            grant = GoogleGrant.ServerAuthCode("the-code"),
            userId = "user-1",
        )

        assertEquals(GoogleGrant.ServerAuthCode("the-code"), redeemer.grant)
        assertEquals("build-time-id", redeemer.clientId)
        assertEquals(pending.codeVerifier, redeemer.codeVerifier)
    }

    /**
     * The prepared request carries what the consent needs — and not the secret half.
     *
     * The request goes into the UI state, and the state is what the screen reads. A
     * verifier in there would be a verifier on the screen.
     */
    @Test
    fun `the prepared request carries the challenge and not the verifier`() = runTest {
        val (service, _, _) = service()

        val pending = service.beginAuthorization().getOrThrow()

        assertEquals("build-time-id", pending.request.clientId)
        assertTrue(pending.request.scopes.isNotEmpty(), "the consent needs scopes")
        assertTrue(pending.request.state.isNotBlank(), "the request needs a fingerprint")
        assertTrue(pending.request.codeChallenge.isNotBlank(), "the request needs a challenge")
        assertTrue(
            pending.request.codeChallenge != pending.codeVerifier,
            "the public half must not be the secret half",
        )
    }

    /** Two requests must not share a fingerprint or a verifier. */
    @Test
    fun `two prepared requests differ`() = runTest {
        val (service, _, _) = service()

        val first = service.beginAuthorization().getOrThrow()
        val second = service.beginAuthorization().getOrThrow()

        assertTrue(first.request.state != second.request.state, "the state must not repeat")
        assertTrue(first.codeVerifier != second.codeVerifier, "the verifier must not repeat")
    }

    /**
     * The pasted client id wins over the build-time one.
     *
     * A user who pastes a client id is standing in front of their own Google Cloud
     * project; the id in the build belongs to whoever packaged it. Using the build-time
     * one anyway would write the user's events into somebody else's project.
     */
    @Test
    fun `a stored client id wins over the build-time one`() = runTest {
        val storage = MapSecureStorage()
        val (service, _, redeemer) = service(storage = storage)
        service.storeClientId("pasted-id")

        val pending = service.beginAuthorization().getOrThrow()
        service.redeem(pending, GoogleGrant.ServerAuthCode("c"), "user-1")

        assertEquals("pasted-id", pending.request.clientId)
        assertEquals("pasted-id", redeemer.clientId)
    }

    /** With nothing stored, the build-time one applies. */
    @Test
    fun `the build-time client id is used when nothing is stored`() = runTest {
        val (service, _, _) = service(buildTimeClientId = "build-time-id")

        val pending = service.beginAuthorization().getOrThrow()

        assertEquals("build-time-id", pending.request.clientId)
    }

    /**
     * No client id at all is a failure that says what to do.
     *
     * The release build ships without one, so this is the *default* state of a fresh
     * install — and it has to be an answer, not a crash or a silent no-op.
     */
    @Test
    fun `a sign-in with no client id fails with a message that says what to do`() = runTest {
        val (service, store, _) = service(buildTimeClientId = null)

        val result = service.beginAuthorization()

        assertTrue(result.isFailure, "a sign-in with no client id must not succeed")
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("client id"), "got $message")
        assertTrue(store.saved.isEmpty(), "nothing may be stored when the sign-in did not happen")
    }

    /** A blank client id is refused rather than stored. */
    @Test
    fun `storing a blank client id is refused`() = runTest {
        val (service, _, _) = service()

        val failure = runCatching { service.storeClientId("  ") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "got $failure")
    }

    /** The configured client id is what the screen asks about. */
    @Test
    fun `the configured client id is reported`() = runTest {
        val (service, _, _) = service(buildTimeClientId = "build-time-id")

        assertEquals("build-time-id", service.configuredClientId())
    }

    /** A failed exchange stores nothing. */
    @Test
    fun `a refused grant stores nothing`() = runTest {
        val redeemer = FakeGrantRedeemer(
            Result.failure(GoogleAuthorizationException("access_denied")),
        )
        val (service, store, _) = service(redeemer = redeemer)
        val pending = service.beginAuthorization().getOrThrow()

        val result = service.redeem(pending, GoogleGrant.ServerAuthCode("c"), "user-1")

        assertTrue(result.isFailure)
        assertTrue(store.saved.isEmpty(), "a refused sign-in must not leave a credential")
    }
}
