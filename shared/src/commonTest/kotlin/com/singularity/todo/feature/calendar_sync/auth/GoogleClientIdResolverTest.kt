package com.singularity.todo.feature.calendar_sync.auth

import com.singularity.todo.core.auth.SecureStorage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory [SecureStorage], so the resolution order can be asserted without a keystore. */
private class MapSecureStorage(private val values: MutableMap<String, String> = mutableMapOf()) : SecureStorage {
    override suspend fun read(key: String): String? = values[key]
    override suspend fun write(key: String, value: String) {
        values[key] = value
    }

    override suspend fun delete(key: String) {
        values.remove(key)
    }
}

/**
 * Which OAuth client id a sign-in uses, and — more importantly — whose it is.
 *
 * The ordering is a security property, not a convenience one: a stored value outranks the
 * build-time one, so a user who pastes their own project's id never has their events sent
 * to the project the APK was built against. That is the assertion these tests exist for.
 */
@Tag("fast")
class GoogleClientIdResolverTest {

    @Test
    fun `returns null when nothing is configured anywhere`() = runTest {
        val resolver = GoogleClientIdResolver(buildTime = { null })
        assertNull(resolver.resolve(MapSecureStorage()))
    }

    @Test
    fun `falls back to the build-time id when nothing is stored`() = runTest {
        val resolver = GoogleClientIdResolver(buildTime = { BuildTimeGoogleClientConfig("build-id") })
        assertEquals("build-id", resolver.resolve(MapSecureStorage())?.clientId)
    }

    @Test
    fun `a stored id outranks the build-time one`() = runTest {
        val resolver = GoogleClientIdResolver(buildTime = { BuildTimeGoogleClientConfig("build-id") })
        val store = MapSecureStorage()
        resolver.store(store, "user-id")
        assertEquals("user-id", resolver.resolve(store)?.clientId)
    }

    @Test
    fun `clearing falls back to the build-time id again`() = runTest {
        val resolver = GoogleClientIdResolver(buildTime = { BuildTimeGoogleClientConfig("build-id") })
        val store = MapSecureStorage()
        resolver.store(store, "user-id")
        resolver.clear(store)
        assertEquals("build-id", resolver.resolve(store)?.clientId)
    }

    @Test
    fun `a blank stored value is ignored rather than used`() = runTest {
        // A stored blank and a stored id read identically at the next resolve, so honouring
        // it would surface as an OAuth error from Google several screens later.
        val resolver = GoogleClientIdResolver(buildTime = { BuildTimeGoogleClientConfig("build-id") })
        val store = MapSecureStorage()
        store.write(GoogleClientIdResolver.KEY_CLIENT_ID, "   ")
        assertEquals("build-id", resolver.resolve(store)?.clientId)
    }

    @Test
    fun `storing a blank id is refused`() = runTest {
        val resolver = GoogleClientIdResolver()
        assertFailsWith<IllegalArgumentException> { resolver.store(MapSecureStorage(), "  ") }
    }

    @Test
    fun `the stored value lands under the documented key`() = runTest {
        val store = MapSecureStorage()
        GoogleClientIdResolver().store(store, "abc")
        assertTrue(store.read(GoogleClientIdResolver.KEY_CLIENT_ID) == "abc")
    }
}
