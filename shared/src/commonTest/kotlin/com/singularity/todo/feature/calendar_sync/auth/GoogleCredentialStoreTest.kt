package com.singularity.todo.feature.calendar_sync.auth

import com.singularity.todo.core.auth.SecureStorage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store's rules exist because of what a calendar sync costs when they are wrong: a
 * lost refresh token is a re-consent, which is a browser, a login, and a permission screen.
 */
@Tag("fast")
class GoogleCredentialStoreTest {

    private class MapSecureStorage(private val map: MutableMap<String, String> = mutableMapOf()) : SecureStorage {
        override suspend fun read(key: String): String? = map[key]
        override suspend fun write(key: String, value: String) {
            map[key] = value
        }

        override suspend fun delete(key: String) {
            map.remove(key)
        }

        fun keys(): Set<String> = map.keys.toSet()
    }

    private val store = MapSecureStorage()
    private val credentials = SecureStorageGoogleCredentialStore(store)

    private fun creds(
        access: String = "access-1",
        refresh: String? = "refresh-1",
        expires: Long = 1_700_003_600_000,
    ) = GoogleCredentials(access, refresh, expires)

    @Test
    fun `a saved credential reads back unchanged`() = runTest {
        credentials.save("user-1", creds())
        val loaded = credentials.load("user-1")
        assertEquals("access-1", loaded?.accessToken)
        assertEquals("refresh-1", loaded?.refreshToken)
        assertEquals(1_700_003_600_000, loaded?.expiresAtEpochMs)
    }

    @Test
    fun `a profile that never authorised is null, not an error`() = runTest {
        assertNull(credentials.load("never-connected"))
    }

    @Test
    fun `profiles do not see each other's credentials`() = runTest {
        credentials.save("work", creds(access = "work-token", refresh = "work-refresh"))
        credentials.save("home", creds(access = "home-token", refresh = "home-refresh"))

        assertEquals("work-token", credentials.load("work")?.accessToken)
        assertEquals("home-token", credentials.load("home")?.accessToken)

        credentials.clear("work")

        assertNull(credentials.load("work"), "clearing one profile must not touch the other")
        assertEquals("home-token", credentials.load("home")?.accessToken)
    }

    /**
     * The rule that protects a re-consent. A later save that carries only an access token
     * — which is exactly what a refresh response looks like, since Google returns a new
     * refresh token only on a fresh grant — must not wipe the durable one.
     */
    @Test
    fun `a later save without a refresh token keeps the existing one`() = runTest {
        credentials.save("user-1", creds(refresh = "the-durable-one"))

        credentials.save(
            "user-1",
            GoogleCredentials(accessToken = "access-2", refreshToken = null, expiresAtEpochMs = 2L),
        )

        assertEquals(
            "the-durable-one",
            credentials.load("user-1")?.refreshToken,
            "a refresh response carries no refresh_token; clobbering here would force a re-consent",
        )
        assertEquals("access-2", credentials.load("user-1")?.accessToken)
    }

    @Test
    fun `a newer refresh token replaces an older one`() = runTest {
        credentials.save("user-1", creds(refresh = "old"))
        credentials.save("user-1", creds(refresh = "new"))
        assertEquals("new", credentials.load("user-1")?.refreshToken)
    }

    /**
     * A grant with no refresh token is still stored: it serves the foreground sync that
     * follows sign-in. Discarding it would make that session impossible.
     */
    @Test
    fun `a grant with no refresh token is stored but reports that it cannot renew`() = runTest {
        credentials.save("user-1", GoogleCredentials("access-only", null, 1L))

        val loaded = credentials.load("user-1")
        assertEquals("access-only", loaded?.accessToken)
        assertFalse(loaded!!.canRenew, "the UI has to be able to say this will need re-authorising")
    }

    @Test
    fun `a credential with a refresh token can renew`() = runTest {
        credentials.save("user-1", creds())
        assertTrue(credentials.load("user-1")!!.canRenew)
    }

    /**
     * Clearing is the only thing standing between "disconnect" and credentials still on
     * disk, because KMPAuth's own sign-out is a no-op on desktop.
     */
    @Test
    fun `clear removes every trace of the credential`() = runTest {
        credentials.save("user-1", creds())
        credentials.clear("user-1")

        assertNull(credentials.load("user-1"))
        assertFalse(
            store.keys().any { it.contains("user-1") },
            "clear left keys behind: ${store.keys()}",
        )
    }

    @Test
    fun `a partial write does not resurrect a half-credential`() = runTest {
        // Access token present, expiry corrupt: this is not a usable credential and must
        // read as absent rather than as a grant that mysteriously never expires.
        store.write(googleCredentialKey("user-1") + ":access", "access-1")
        store.write(googleCredentialKey("user-1") + ":expires", "not-a-number")

        assertNull(credentials.load("user-1"))
    }

    @Test
    fun `the key is namespaced so it cannot collide with another secret`() {
        assertTrue(googleCredentialKey("user-1").contains("user-1"))
        assertTrue(googleCredentialKey("user-1").startsWith("google_calendar:"))
    }
}
