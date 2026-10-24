package com.singularity.todo.feature.calendarSync.auth

import com.singularity.todo.core.auth.SecureStorage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's revoke: the credential has to stop working, not just be forgotten.
 *
 * ## What is at stake
 *
 * The desktop keeps its refresh token in the user's own keystore. Forgetting it there
 * leaves a token that still works at Google — and the person who pressed "disconnect"
 * has no way to cancel it, because they cannot see it.
 *
 * The revoke is therefore the whole promise, and it has to happen *before* the
 * credential store is cleared: a device that has already forgotten the token cannot
 * cancel what it handed out.
 */
@Tag("fast")
class LocalGoogleGrantRevokerTest {

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

    private class FakeCredentialStore(initial: GoogleCredentials? = null) : GoogleCredentialStore {
        var stored: GoogleCredentials? = initial
            private set

        override suspend fun load(userId: String): GoogleCredentials? = stored

        override suspend fun save(userId: String, credentials: GoogleCredentials) {
            stored = credentials
        }

        override suspend fun clear(userId: String) {
            stored = null
        }
    }

    private class FakeTokenExchange(private val failure: Throwable? = null) : GoogleTokenExchange {
        /** The token the revoke was asked to cancel, or null when it never ran. */
        var revokedWith: String? = null
            private set

        override suspend fun exchangeCode(
            code: String,
            redirectUri: String,
            codeVerifier: String?,
        ): GoogleCredentials = throw IllegalStateException("not used")

        override suspend fun refresh(refreshToken: String): GoogleCredentials =
            throw IllegalStateException("not used")

        override suspend fun revoke(refreshToken: String) {
            revokedWith = refreshToken
            failure?.let { throw it }
        }
    }

    private fun revoker(
        store: GoogleCredentialStore,
        exchange: GoogleTokenExchange,
        clientId: String? = "the-client-id",
    ) = LocalGoogleGrantRevoker(
        credentialStore = store,
        clientIdResolver = GoogleClientIdResolver(
            buildTime = { clientId?.let { BuildTimeGoogleClientConfig(it) } },
        ),
        secureStorage = MapSecureStorage(),
        tokenClientFactory = { exchange },
    )

    /** The token on the device is cancelled at Google. */
    @Test
    fun `the stored refresh token is revoked at Google`() = runTest {
        val store = FakeCredentialStore(GoogleCredentials("access", "the-refresh", Long.MAX_VALUE))
        val exchange = FakeTokenExchange()

        val result = revoker(store, exchange).revoke("user-1")

        assertTrue(result.isSuccess, "the revoke must succeed: $result")
        assertEquals("the-refresh", exchange.revokedWith)
    }

    /**
     * An account that was never connected has nothing to revoke.
     *
     * Reporting a failure for it would teach the caller that "disconnect" is broken
     * when it is exactly what the person wanted.
     */
    @Test
    fun `an account with no credential is already disconnected`() = runTest {
        val exchange = FakeTokenExchange()

        val result = revoker(FakeCredentialStore(null), exchange).revoke("user-1")

        assertTrue(result.isSuccess, "nothing to revoke is not a failure: $result")
        assertNull(exchange.revokedWith, "there was no token to cancel")
    }

    /** A credential with no refresh token cannot be revoked — and there is nothing to cancel. */
    @Test
    fun `a credential with no refresh token has nothing to revoke`() = runTest {
        val store = FakeCredentialStore(GoogleCredentials("access", null, Long.MAX_VALUE))
        val exchange = FakeTokenExchange()

        val result = revoker(store, exchange).revoke("user-1")

        assertTrue(result.isSuccess, "nothing to revoke is not a failure: $result")
        assertNull(exchange.revokedWith)
    }

    /**
     * A revoke Google refuses is a failure the caller has to hear about.
     *
     * The device is disconnected either way, but the token is still alive somewhere —
     * and the user is the only one who can cancel it.
     */
    @Test
    fun `a refused revoke is reported`() = runTest {
        val store = FakeCredentialStore(GoogleCredentials("access", "the-refresh", Long.MAX_VALUE))
        val exchange = FakeTokenExchange(failure = IllegalStateException("Google refused"))

        val result = revoker(store, exchange).revoke("user-1")

        assertTrue(result.isFailure, "a refused revoke must not look like a success")
        assertNotNull(result.exceptionOrNull())
    }

    /**
     * Without a client id the token cannot be presented, and that has to be said.
     *
     * The credential is still on the device and still alive at Google; a silent success
     * would be a lie about a grant that nobody cancelled.
     */
    @Test
    fun `a missing client id is reported instead of quietly skipping the revoke`() = runTest {
        val store = FakeCredentialStore(GoogleCredentials("access", "the-refresh", Long.MAX_VALUE))
        val exchange = FakeTokenExchange()

        val result = revoker(store, exchange, clientId = null).revoke("user-1")

        assertTrue(result.isFailure, "a grant that was not revoked must not look revoked")
        assertNull(exchange.revokedWith)
    }
}
