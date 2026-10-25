package com.singularity.todo.core.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The credential store, on its own.
 *
 * The store used to be four keychain values and a migration out of plain-text
 * preferences; it is now one value, and the migration is the interesting part: it
 * moves a session that exists somewhere into the one place the application reads,
 * and it must never destroy a token it failed to move.
 */
@Tag("fast")
class SecureSessionManagerTest {

    private fun manager(
        secure: SecureStorage,
        legacy: DataStoreSessionStore,
    ): SecureSessionManager = SecureSessionManager(
        log = testLogger(),
        secure = secure,
        legacy = legacy,
    )

    private fun legacyStore(preferences: DataStore<Preferences> = newPreferencesStore()) =
        DataStoreSessionStore(preferences, FixedIdGenerator("dev"))

    private fun session(
        owner: String = "owner-1",
        email: String = "a@b.c",
        access: String = "access-1",
        refresh: String = "refresh-1",
    ) = Session.SignedIn(UserId.fromString(owner), email, access, refresh)

    /** The plain-text copies an older build left behind. */
    private suspend fun seedPlainText(
        preferences: DataStore<Preferences>,
        access: String = "access-plain",
        refresh: String = "refresh-plain",
        email: String = "a@b.c",
    ) {
        preferences.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(DataStoreSessionStore.ACCESS_TOKEN, access)
                set(DataStoreSessionStore.REFRESH_TOKEN, refresh)
                set(DataStoreSessionStore.USER_EMAIL, email)
            }
        }
    }

    @Test
    fun `a session is kept in one value and read back`() = runTest {
        val secure = MapSecureStorage()
        val legacy = legacyStore()
        val store = manager(secure, legacy)

        store.save(session())

        assertEquals(1, secure.values.size, "the session is one value, not four")
        assertEquals(session(), store.currentSession())
        assertNull(legacy.accessToken.first(), "the plain-text preferences hold no token")
    }

    @Test
    fun `a second write replaces the first and does not grow the store`() = runTest {
        val secure = MapSecureStorage()
        val store = manager(secure, legacyStore())

        store.save(session())
        store.save(session(owner = "owner-2", access = "access-2", refresh = "refresh-2"))

        assertEquals(1, secure.values.size, "the number of stored values does not grow")
        assertEquals("access-2", store.currentSession()?.accessToken)
        assertEquals("refresh-2", store.currentSession()?.refreshToken)
        assertEquals(UserId.fromString("owner-2"), store.currentSession()?.userId)
    }

    @Test
    fun `a failed write leaves no session behind and names the local cause`() = runTest {
        val secure = MapSecureStorage(failWrites = true)
        val store = manager(secure, legacyStore())

        val failure = assertFailsWith<AppError.Persistence> { store.save(session()) }

        assertEquals(SecureSessionManager.STORAGE_FAILED, failure.code)
        assertNull(store.currentSession(), "a write that failed leaves nothing behind")
    }

    @Test
    fun `the previous layout is migrated and remains usable`() = runTest {
        val secure = MapSecureStorage()
        secure.values[SecureSessionManager.LEGACY_KEY_ACCESS] = "access-old"
        secure.values[SecureSessionManager.LEGACY_KEY_REFRESH] = "refresh-old"
        secure.values[SecureSessionManager.LEGACY_KEY_EMAIL] = "a@b.c"
        secure.values[SecureSessionManager.LEGACY_KEY_USER_ID] = "owner-old"
        val store = manager(secure, legacyStore())

        val migrated = store.currentSession()

        assertNotNull(migrated, "the session survived the move")
        assertEquals("access-old", migrated.accessToken)
        assertEquals("refresh-old", migrated.refreshToken)
        assertEquals(UserId.fromString("owner-old"), migrated.userId)
        assertEquals(1, secure.values.size, "the current layout holds one value")
        assertNull(secure.values[SecureSessionManager.LEGACY_KEY_ACCESS], "the old values are erased")
        assertNull(secure.values[SecureSessionManager.LEGACY_KEY_REFRESH])
        assertNull(secure.values[SecureSessionManager.LEGACY_KEY_EMAIL])
        assertNull(secure.values[SecureSessionManager.LEGACY_KEY_USER_ID])
    }

    @Test
    fun `a record without an owner is kept as a token-only record`() = runTest {
        val secure = MapSecureStorage()
        secure.values[SecureSessionManager.LEGACY_KEY_ACCESS] = "access-old"
        secure.values[SecureSessionManager.LEGACY_KEY_REFRESH] = "refresh-old"
        secure.values[SecureSessionManager.LEGACY_KEY_EMAIL] = "a@b.c"
        val store = manager(secure, legacyStore())

        val migrated = store.currentSession()

        assertNull(migrated, "a record with no owner is not a session")
        assertEquals(1, secure.values.size, "the token is still kept, in the current layout")
        assertTrue(
            secure.values[SecureSessionManager.KEY_SESSION].orEmpty().contains("access-old"),
            "the token moved rather than being dropped",
        )
        assertNull(secure.values[SecureSessionManager.LEGACY_KEY_ACCESS], "the old values are erased")
    }

    @Test
    fun `a plain-text token is moved into the store and erased`() = runTest {
        val secure = MapSecureStorage()
        val preferences = newPreferencesStore()
        val legacy = legacyStore(preferences)
        seedPlainText(preferences)
        val store = manager(secure, legacy)

        val migrated = store.currentSession()

        assertNull(migrated, "a record without an owner is not a session")
        assertEquals(1, secure.values.size, "the token moved into the secure store")
        assertTrue(
            secure.values[SecureSessionManager.KEY_SESSION].orEmpty().contains("access-plain"),
            "the token moved rather than being dropped",
        )
        assertNull(legacy.accessToken.first(), "the plain-text access token is gone")
        assertNull(legacy.refreshToken.first(), "the plain-text refresh token is gone")
        assertNull(legacy.userEmail.first(), "the plain-text address is gone")
    }

    @Test
    fun `a session already in the current layout is never displaced`() = runTest {
        val secure = MapSecureStorage()
        val preferences = newPreferencesStore()
        val legacy = legacyStore(preferences)
        val store = manager(secure, legacy)
        store.save(session(access = "access-new", refresh = "refresh-new"))
        // The older copies: the previous layout's four values, and a plain-text
        // token older than both.
        secure.values[SecureSessionManager.LEGACY_KEY_ACCESS] = "access-older"
        secure.values[SecureSessionManager.LEGACY_KEY_REFRESH] = "refresh-older"
        secure.values[SecureSessionManager.LEGACY_KEY_USER_ID] = "owner-older"
        secure.values[SecureSessionManager.LEGACY_KEY_EMAIL] = "old@b.c"
        seedPlainText(preferences, access = "access-oldest")

        // A second store over the same keychain: the migration runs on the first
        // read, and the current layout is already there.
        val fresh = manager(secure, legacy)

        val served = fresh.currentSession()

        assertNotNull(served)
        assertEquals("access-new", served.accessToken, "the newest session wins")
        assertEquals("refresh-new", served.refreshToken)
        assertNull(secure.values[SecureSessionManager.LEGACY_KEY_ACCESS], "the older copies are erased")
        assertNull(legacy.accessToken.first(), "the plain-text copy is erased")
    }

    @Test
    fun `a failed move erases nothing`() = runTest {
        val secure = MapSecureStorage(failWrites = true)
        secure.values[SecureSessionManager.LEGACY_KEY_ACCESS] = "access-old"
        secure.values[SecureSessionManager.LEGACY_KEY_REFRESH] = "refresh-old"
        secure.values[SecureSessionManager.LEGACY_KEY_USER_ID] = "owner-old"
        val store = manager(secure, legacyStore())

        val migrated = store.currentSession()

        assertNull(migrated, "a move that failed leaves no session to serve")
        assertEquals(
            "access-old",
            secure.values[SecureSessionManager.LEGACY_KEY_ACCESS],
            "the token stays where it was — a lost token is a lost account",
        )
        assertEquals(
            "refresh-old",
            secure.values[SecureSessionManager.LEGACY_KEY_REFRESH],
            "the token stays where it was — a lost token is a lost account",
        )
    }

    @Test
    fun `the store is cleared on sign-out`() = runTest {
        val secure = MapSecureStorage()
        val preferences = newPreferencesStore()
        val legacy = legacyStore(preferences)
        val store = manager(secure, legacy)
        store.save(session())

        store.deleteSession()

        assertNull(store.currentSession(), "the store holds nothing after a sign-out")
        assertNull(legacy.accessToken.first(), "the plain-text copies are gone as well")
    }

    @Test
    fun `a write racing a delete does not resurrect the session`() = runTest {
        val secure = GatedWriteSecureStorage()
        val store = manager(secure, legacyStore())

        // The write is held open inside the keychain, so the delete arrives while it
        // is in flight. Started undispatched, so that the write is already in flight
        // when the delete asks for its turn.
        val save = async(start = CoroutineStart.UNDISPATCHED) { store.save(session()) }
        secure.writeWaiting.await()

        val delete = async(start = CoroutineStart.UNDISPATCHED) { store.deleteSession() }
        assertFalse(
            delete.isCompleted,
            "the store serialises its operations: the delete must not run while a write is in flight",
        )

        secure.releaseTheWrite()
        awaitAll(save, delete)

        assertNull(
            secure.values[SecureSessionManager.KEY_SESSION],
            "the delete wins: the store holds nothing",
        )
        assertNull(store.currentSession(), "a session that reappears after a sign-out cannot be ended")
    }

    @Test
    fun `the migration runs once, not on every read`() = runTest {
        val secure = MapSecureStorage()
        secure.values[SecureSessionManager.LEGACY_KEY_ACCESS] = "access-old"
        secure.values[SecureSessionManager.LEGACY_KEY_REFRESH] = "refresh-old"
        secure.values[SecureSessionManager.LEGACY_KEY_USER_ID] = "owner-old"
        val preferences = newPreferencesStore()
        val legacy = legacyStore(preferences)
        val store = manager(secure, legacy)

        val first = store.currentSession()
        val writesAfterTheMigration = secure.writeAttempts

        // A different plain-text token appears after the migration has run. A second
        // migration would move it into the store and erase it.
        seedPlainText(preferences, access = "access-planted", refresh = "refresh-planted")

        val second = store.currentSession()

        assertEquals(first, second, "the second read serves the session the first one migrated")
        assertEquals(writesAfterTheMigration, secure.writeAttempts, "the migration did not run again")
        assertEquals(
            "access-planted",
            legacy.accessToken.first(),
            "the planted value stayed where it was — a second migration would have moved it",
        )
    }

    @Test
    fun `two concurrent first reads migrate once`() = runTest {
        val secure = MapSecureStorage()
        secure.values[SecureSessionManager.LEGACY_KEY_ACCESS] = "access-old"
        secure.values[SecureSessionManager.LEGACY_KEY_REFRESH] = "refresh-old"
        secure.values[SecureSessionManager.LEGACY_KEY_USER_ID] = "owner-old"
        val store = manager(secure, legacyStore())

        val first = async { store.currentSession() }
        val second = async { store.currentSession() }
        val served = listOf(first, second).awaitAll()

        assertEquals(1, secure.writeAttempts, "the migration was performed once, not twice")
        assertEquals(
            listOf("access-old", "access-old"),
            served.map { it?.accessToken },
            "both readers were served the migrated session",
        )
    }

    @Test
    fun `a cancellation between the migration's write and its erase leaves the session in one place`() = runTest {
        val secure = GatedDeleteSecureStorage()
        secure.values[SecureSessionManager.LEGACY_KEY_ACCESS] = "access-old"
        secure.values[SecureSessionManager.LEGACY_KEY_REFRESH] = "refresh-old"
        secure.values[SecureSessionManager.LEGACY_KEY_USER_ID] = "owner-old"
        val store = manager(secure, legacyStore())

        // The first launch writes the record into the current layout and then hangs on
        // the erase of the old copy. The launch ends there.
        val first = launch { store.currentSession() }
        secure.deleteWaiting.await()
        first.cancelAndJoin()

        assertTrue(
            secure.values[SecureSessionManager.KEY_SESSION].orEmpty().contains("access-old"),
            "the record reached the current layout before the launch ended",
        )
        assertEquals(
            "access-old",
            secure.values[SecureSessionManager.LEGACY_KEY_ACCESS],
            "the old copy is still there — only the erase is missing",
        )

        // The next launch finds the record in the current layout, serves it, and erases
        // the old copy without importing it.
        secure.releaseTheDelete()
        val served = store.currentSession()

        assertNotNull(served, "the migrated session is served")
        assertEquals("access-old", served.accessToken)
        assertNull(
            secure.values[SecureSessionManager.LEGACY_KEY_ACCESS],
            "the leftover is erased by the next launch",
        )
    }
}
