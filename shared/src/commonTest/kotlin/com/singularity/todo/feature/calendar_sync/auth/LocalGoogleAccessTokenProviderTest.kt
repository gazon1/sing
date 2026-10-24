package com.singularity.todo.feature.calendar_sync.auth

import com.singularity.todo.core.auth.SecureStorage
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's access token, renewed on the device.
 *
 * ## What is at stake
 *
 * An access token lasts about an hour and the calendar sync runs unattended. The
 * renewal is therefore not an optimisation: without it the second background run
 * fails, and the user sees a sync that "worked once".
 */
@Tag("fast")
class LocalGoogleAccessTokenProviderTest {

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

        var cleared = false
            private set

        override suspend fun load(userId: String): GoogleCredentials? = stored

        override suspend fun save(userId: String, credentials: GoogleCredentials) {
            stored = credentials
        }

        override suspend fun clear(userId: String) {
            stored = null
            cleared = true
        }
    }

    private class FakeTokenExchange(
        private val renewed: GoogleCredentials = GoogleCredentials("new-access", "new-refresh", 99L),
    ) : GoogleTokenExchange {
        var refreshedWith: String? = null
            private set

        override suspend fun exchangeCode(
            code: String,
            redirectUri: String,
            codeVerifier: String?,
        ): GoogleCredentials = renewed

        override suspend fun refresh(refreshToken: String): GoogleCredentials {
            refreshedWith = refreshToken
            return renewed
        }
    }

    private fun provider(
        store: GoogleCredentialStore,
        exchange: GoogleTokenExchange,
        clientId: String? = "the-client-id",
    ) = LocalGoogleAccessTokenProvider(
        credentialStore = store,
        clientIdResolver = GoogleClientIdResolver(
            buildTime = { clientId?.let { BuildTimeGoogleClientConfig(it) } },
        ),
        secureStorage = MapSecureStorage(),
        tokenClientFactory = { exchange },
    )

    /** No credential at all means "not connected", not a crash. */
    @Test
    fun `a profile with no credential has no token`() = runTest {
        val provider = provider(FakeCredentialStore(), FakeTokenExchange())

        assertNull(provider.accessToken(UserId.fromString("user-1")))
    }

    /** A live token is used as it is. */
    @Test
    fun `a live token is used without renewing it`() = runTest {
        val store = FakeCredentialStore(
            GoogleCredentials("the-token", "the-refresh", System.currentTimeMillis() + 60_000),
        )
        val exchange = FakeTokenExchange()
        val provider = provider(store, exchange)

        assertEquals("the-token", provider.accessToken(UserId.fromString("user-1")))
        assertNull(exchange.refreshedWith, "a live token must not be renewed")
    }

    /** An expired token is renewed with the stored refresh token. */
    @Test
    fun `an expired token is renewed`() = runTest {
        val store = FakeCredentialStore(
            GoogleCredentials("old-token", "the-refresh", System.currentTimeMillis() - 1),
        )
        val exchange = FakeTokenExchange()
        val provider = provider(store, exchange)

        assertEquals("new-access", provider.accessToken(UserId.fromString("user-1")))
        assertEquals("the-refresh", exchange.refreshedWith)
    }

    /** The renewed credential is stored, so the next call does not renew again. */
    @Test
    fun `the renewed credential is stored`() = runTest {
        val store = FakeCredentialStore(
            GoogleCredentials("old-token", "the-refresh", System.currentTimeMillis() - 1),
        )
        val provider = provider(store, FakeTokenExchange())

        provider.accessToken(UserId.fromString("user-1"))

        assertEquals("new-access", store.stored?.accessToken)
    }

    /**
     * A grant that cannot be renewed is cleared.
     *
     * The alternative is a credential that fails every call from now on, with the
     * screen still saying "connected" — and the user unable to tell that they have
     * to connect again.
     */
    @Test
    fun `a grant that cannot be renewed is cleared`() = runTest {
        val store = FakeCredentialStore(
            GoogleCredentials("old-token", "the-refresh", System.currentTimeMillis() - 1),
        )
        val exchange = object : GoogleTokenExchange {
            override suspend fun exchangeCode(
                code: String,
                redirectUri: String,
                codeVerifier: String?,
            ): GoogleCredentials = throw IllegalStateException("not used")

            override suspend fun refresh(refreshToken: String): GoogleCredentials =
                throw IllegalStateException("the grant is gone")
        }
        val provider = provider(store, exchange)

        assertNull(provider.accessToken(UserId.fromString("user-1")))
        assertTrue(store.cleared, "the dead credential must not be left on the device")
    }

    /** A credential with no refresh token cannot be renewed; it is simply not usable. */
    @Test
    fun `an expired credential with no refresh token yields no token`() = runTest {
        val store = FakeCredentialStore(
            GoogleCredentials("old-token", null, System.currentTimeMillis() - 1),
        )
        val provider = provider(store, FakeTokenExchange())

        assertNull(provider.accessToken(UserId.fromString("user-1")))
    }
}
