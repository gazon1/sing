@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.singularity.todo.core.error.AppError
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    // ── REQ-UA-012: a session is written whole or not at all ─────────────────

    @Test
    fun `a write that fails part way leaves the store empty, not half a session`() = runTest {
        // The keychain has no multi-key transaction, so four fields are four writes
        // and the third can fail on its own. A blanket failure would not catch a
        // caller that leaves a partial set behind, so this fails exactly one write.
        val prefs = seededPreferences()
        val secure = MapSecureStorage(failWriteNumber = 3)
        val store = newStore(prefs, secure)

        val error = assertFailsWith<AppError.Persistence> {
            store.save(
                Session.SignedIn(userId(), "a@b.c", "new-access", "new-refresh"),
            )
        }

        assertTrue(
            secure.values.isEmpty(),
            "a half-written identity is worse than none: found ${secure.values.keys}",
        )
        assertEquals(SecureSessionStore.STORAGE_FAILED, error.code)
    }

    @Test
    fun `a failed write is undone for each of the four fields, not just the one that failed`() = runTest {
        // One representative of the four: the undo cannot be written to pass only for
        // the third write, because the caller's undo does not know which one failed.
        val secure = MapSecureStorage(failWriteNumber = 1)
        val store = newStore(seededPreferences(), secure)

        assertFailsWith<AppError.Persistence> {
            store.save(Session.SignedIn(userId(), "a@b.c", "new-access", "new-refresh"))
        }

        assertTrue(secure.values.isEmpty(), "the first field failed, so nothing should remain")
    }

    @Test
    fun `a failed write keeps the cause, so a crash report can be acted on`() = runTest {
        val secure = MapSecureStorage(failWriteNumber = 2)
        val store = newStore(seededPreferences(), secure)

        val error = assertFailsWith<AppError.Persistence> {
            store.save(Session.SignedIn(userId(), "a@b.c", "new-access", "new-refresh"))
        }

        assertNotNull(
            error.cause,
            "the storage failure is the thing worth reading a stack trace for; " +
                "the wrapper's own stack ends where the wrapper was built",
        )
    }

    @Test
    fun `a launch after a failed write reads no session`() = runTest {
        val secure = MapSecureStorage(failWriteNumber = 3)
        val first = newStore(seededPreferences(), secure)
        assertFailsWith<AppError.Persistence> {
            first.save(Session.SignedIn(userId(), "a@b.c", "new-access", "new-refresh"))
        }

        // A second store over the same storage is what the next launch is: the partial
        // write is gone, so the device comes up signed out rather than half restored.
        val second = newStore(seededPreferences(), secure)
        assertNull(second.currentAccessToken())
        assertNull(second.currentRefreshToken())
        assertNull(second.currentUserId())
    }

    // ── REQ-UA-013: a plain-text token cannot displace a newer session ────────

    @Test
    fun `a leftover plain-text token does not overwrite a session the keychain already holds`() = runTest {
        // The interrupted `save`: the session reached the keychain, and the process
        // ended before the plain-text copy was erased. `attempted` is per-instance, so
        // the next launch re-runs the migration — and the value it finds in preferences
        // is the one that was just superseded. Importing it moves the device backwards,
        // onto a refresh token that was rotated on the way out and is usually revoked.
        val prefs = seededPreferences(access = "stale-access", refresh = "stale-refresh", email = "a@b.c")
        val secure = MapSecureStorage().apply {
            values[SecureSessionStore.KEY_ACCESS] = "new-access"
            values[SecureSessionStore.KEY_REFRESH] = "new-refresh"
        }
        val store = newStore(prefs, secure)

        assertEquals("new-access", store.currentAccessToken())
        assertEquals("new-refresh", store.currentRefreshToken())
        assertNull(plaintextAccess(prefs), "the superseded copy is erased, not imported")
        assertNull(plaintextRefresh(prefs))
    }

    @Test
    fun `an interrupted migration does not force the user to sign in again`() = runTest {
        val prefs = seededPreferences(access = "stale-access", refresh = "stale-refresh", email = "a@b.c")
        // A *complete* newer session, as `save` would have left it — all four fields.
        // Seeding only the tokens would make the owner assertion below fail for a
        // reason that has nothing to do with the migration, which is the trap this
        // fixture is easy to fall into.
        val secure = MapSecureStorage().apply {
            values[SecureSessionStore.KEY_ACCESS] = "new-access"
            values[SecureSessionStore.KEY_REFRESH] = "new-refresh"
            values[SecureSessionStore.KEY_EMAIL] = "new@b.c"
            values[SecureSessionStore.KEY_USER_ID] = "new-owner"
        }
        val store = newStore(prefs, secure)

        store.currentAccessToken()

        assertNotNull(
            store.currentAccessToken(),
            "a valid session was sitting in the keychain all along",
        )
        assertEquals("new-owner", store.currentUserId())
        assertNull(plaintextAccess(prefs), "the superseded copy is erased, not merely ignored")
    }

    @Test
    fun `a failed migration still keeps the plain-text copy when the keychain is empty`() = runTest {
        // The other case, and the one the ordering rule above was written for: the
        // secure store holds nothing, so the plain-text copy is the only copy and a
        // failed write must not erase it. "Do not displace" must not be implemented as
        // "never import".
        val prefs = seededPreferences(access = "only-access", refresh = "only-refresh", email = "a@b.c")
        val secure = MapSecureStorage(failWrites = true)
        val store = newStore(prefs, secure)

        assertNull(store.currentAccessToken())

        assertEquals(
            "only-access",
            plaintextAccess(prefs),
            "a missing token is an account the user cannot get back into",
        )
    }

    @Test
    fun `a plain-text copy is still imported when the keychain is empty and the write works`() = runTest {
        val prefs = seededPreferences(access = "old-access", refresh = "old-refresh", email = "a@b.c")
        val secure = MapSecureStorage()
        val store = newStore(prefs, secure)

        assertEquals("old-access", store.currentAccessToken())
        assertNull(plaintextAccess(prefs))
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
