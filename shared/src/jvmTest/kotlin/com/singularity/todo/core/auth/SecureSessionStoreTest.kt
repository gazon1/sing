@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moving the session token out of plain-text preferences and into the keychain.
 *
 * ## The property under test
 *
 * Not "the token ends up in the keychain" — that is the easy half. The load-bearing
 * one is the **order**: the value is written to its new home before the old one is
 * erased. Every other property here follows from that, and a single reversed
 * operation turns an upgrade into an account loss with no way back.
 */
@Tag("fast")
class SecureSessionStoreTest {

    // ── The migration ────────────────────────────────────────────────────────

    @Test
    fun `a token left in preferences is moved into the keychain and erased`() = runTest {
        val prefs = seededPreferences(access = "old-access", refresh = "old-refresh", email = "a@b.c")
        val secure = MapSecureStorage()
        val store = newStore(prefs, secure)

        assertEquals("old-access", store.currentAccessToken())

        assertEquals("old-access", secure.values[SecureSessionStore.KEY_ACCESS])
        assertEquals("old-refresh", secure.values[SecureSessionStore.KEY_REFRESH])
        assertEquals("a@b.c", secure.values[SecureSessionStore.KEY_EMAIL])
        assertNull(plaintextAccess(prefs), "the plain-text copy is the thing being removed")
        assertNull(plaintextRefresh(prefs))
    }

    @Test
    fun `nothing is erased when there was nothing to move`() = runTest {
        val prefs = seededPreferences()
        val secure = MapSecureStorage()
        val store = newStore(prefs, secure)

        assertNull(store.currentAccessToken())

        assertTrue(secure.values.isEmpty(), "no migration should write anything")
    }

    @Test
    fun `a failed secure write leaves the plain-text token in place`() = runTest {
        // The whole reason for write-then-erase. Erasing first and then failing
        // leaves the user with no token anywhere: signed out of their own account,
        // with the copy that would have let them back already deleted.
        val prefs = seededPreferences(access = "old-access")
        val secure = MapSecureStorage(failWrites = true)
        val store = newStore(prefs, secure)

        assertNull(store.currentAccessToken())

        assertEquals("old-access", plaintextAccess(prefs))
        assertTrue(
            plaintextAccess(prefs) == "old-access",
            "a keychain that refused the write must not cost the user their session",
        )
    }

    @Test
    fun `the migration happens once, not on every read`() = runTest {
        val prefs = seededPreferences(access = "old-access")
        val secure = MapSecureStorage()
        val store = newStore(prefs, secure)

        assertEquals("old-access", store.currentAccessToken())
        // A value appearing in preferences afterwards is not re-imported: that
        // would let anything able to write preferences overwrite a live session.
        prefs.edit { it[DataStoreSessionStore.ACCESS_TOKEN] = "planted" }

        assertEquals("old-access", store.currentAccessToken())
        assertEquals("planted", plaintextAccess(prefs))
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    @Test
    fun `saving a session writes to the keychain and never to preferences`() = runTest {
        val prefs = seededPreferences()
        val secure = MapSecureStorage()
        val store = newStore(prefs, secure)

        store.save(Session.SignedIn(userId(), "x@y.z", "fresh-access", "fresh-refresh"))

        assertEquals("fresh-access", secure.values[SecureSessionStore.KEY_ACCESS])
        assertEquals("fresh-refresh", secure.values[SecureSessionStore.KEY_REFRESH])
        assertEquals("x@y.z", secure.values[SecureSessionStore.KEY_EMAIL])
        assertNull(plaintextAccess(prefs), "a new token must never be written in plain text")
        assertNull(plaintextRefresh(prefs))
        assertNull(plaintextEmail(prefs))
    }

    @Test
    fun `a save is not overwritten by the migration that was still pending`() = runTest {
        // The ordering hazard: a store that had never been read, receiving a
        // sign-in, must end up holding the new token — not the old one that a
        // late migration wrote afterwards.
        val prefs = seededPreferences(access = "stale-access")
        val secure = MapSecureStorage()
        val store = newStore(prefs, secure)

        store.save(Session.SignedIn(userId(), "x@y.z", "fresh-access", "fresh-refresh"))

        assertEquals("fresh-access", secure.values[SecureSessionStore.KEY_ACCESS])
        assertEquals("fresh-access", store.currentAccessToken())
    }

    @Test
    fun `saving removes a plain-text copy a pre-upgrade build left behind`() = runTest {
        val prefs = seededPreferences(access = "old-access", refresh = "old-refresh", email = "a@b.c")
        val secure = MapSecureStorage()
        val store = newStore(prefs, secure)

        store.save(Session.SignedIn(userId(), "x@y.z", "fresh-access", "fresh-refresh"))

        assertNull(plaintextAccess(prefs))
        assertNull(plaintextRefresh(prefs))
        assertNull(plaintextEmail(prefs))
    }

    @Test
    fun `clearing removes the token from both homes`() = runTest {
        val prefs = seededPreferences(access = "old-access")
        val secure = MapSecureStorage()
        val store = newStore(prefs, secure)
        store.currentAccessToken()

        store.clear()

        assertNull(secure.values[SecureSessionStore.KEY_ACCESS])
        assertNull(plaintextAccess(prefs))
    }

    @Test
    fun `the device id is served from preferences, not the keychain`() = runTest {
        val prefs = seededPreferences()
        val secure = MapSecureStorage()
        val store = newStore(prefs, secure)

        val id = store.getOrInitDeviceId()

        assertTrue(id.isNotBlank())
        assertEquals(id, store.deviceId.value)
        assertTrue(
            secure.values.keys.none { it.contains("device") },
            "the device id is an identifier, not a credential; a keyring reset must not change it",
        )
    }

    // ── Infrastructure ───────────────────────────────────────────────────────

    private fun newStore(prefs: DataStore<Preferences>, secure: SecureStorage) = SecureSessionStore(
        log = testLogger(),
        secure = secure,
        legacy = DataStoreSessionStore(prefs, FixedIdGenerator("device-fixed")),
    )

    private suspend fun seededPreferences(
        access: String? = null,
        refresh: String? = null,
        email: String? = null,
    ): DataStore<Preferences> = newPreferencesStore().apply {
        edit { p ->
            access?.let { p[DataStoreSessionStore.ACCESS_TOKEN] = it }
            refresh?.let { p[DataStoreSessionStore.REFRESH_TOKEN] = it }
            email?.let { p[DataStoreSessionStore.USER_EMAIL] = it }
        }
    }

    private suspend fun plaintextAccess(prefs: DataStore<Preferences>) =
        prefs.data.first()[DataStoreSessionStore.ACCESS_TOKEN]

    private suspend fun plaintextRefresh(prefs: DataStore<Preferences>) =
        prefs.data.first()[DataStoreSessionStore.REFRESH_TOKEN]

    private suspend fun plaintextEmail(prefs: DataStore<Preferences>) =
        prefs.data.first()[DataStoreSessionStore.USER_EMAIL]

    private fun userId() = com.singularity.todo.core.ids.UserId.fromString("user-1")
}
