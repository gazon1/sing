package com.singularity.todo.core.auth

import com.singularity.todo.test.fakes.SequenceIdGenerator
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Where the app decides which Supabase project to talk to.
 *
 * The rule under test is that a stored configuration wins over a build-time one. It
 * sounds like trivia and is not: a debug build with a developer's test project
 * compiled in must not silently override the project a user typed on a shared device,
 * because the symptom is "my data went somewhere else" and nothing in the app says so.
 */
@Tag("fast")
class SupabaseConfigResolverTest {

    private class MapStore : SecureStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String): String? = values[key]
        override suspend fun write(key: String, value: String) {
            values[key] = value
        }

        override suspend fun delete(key: String) {
            values.remove(key)
        }
    }

    private fun resolver(buildTime: BuildTimeSupabaseConfig?) = SupabaseConfigResolver(
        idGenerator = SequenceIdGenerator("unused"),
        buildTime = { buildTime },
    )

    private val devConfig = BuildTimeSupabaseConfig("https://dev.example", "dev-key")

    @Test
    fun `nothing configured means no configuration, not a crash`() = runTest {
        // A fresh install has no server. Throwing here would make "not set up yet" a
        // crash on the first frame rather than a state the app can show.
        assertNull(resolver(buildTime = null).resolve(MapStore()))
    }

    @Test
    fun `the build-time value is used when nothing is stored`() = runTest {
        val config = resolver(devConfig).resolve(MapStore())

        assertEquals("https://dev.example", config?.url)
        assertEquals("dev-key", config?.anonKey)
    }

    @Test
    fun `a stored configuration wins over the build-time one`() = runTest {
        val store = MapStore()
        resolver(devConfig).store(store, SupabaseConfig("https://mine.example", "mine-key"))

        val resolved = resolver(devConfig).resolve(store)

        assertEquals("https://mine.example", resolved?.url, "the user's own project must win")
        assertEquals("mine-key", resolved?.anonKey)
    }

    @Test
    fun `a half-stored configuration is treated as nothing stored`() = runTest {
        val store = MapStore().apply { values[SupabaseConfigResolver.KEY_URL] = "https://mine.example" }

        // A url with no key is indistinguishable from "not configured" until the first
        // request fails, and that failure arrives as a network error that looks like a
        // server problem. Falling back is the better of the two wrong answers.
        assertEquals("https://dev.example", resolver(devConfig).resolve(store)?.url)
    }

    @Test
    fun `a half configuration is refused rather than stored`() = runTest {
        val store = MapStore()

        assertFailsWith<IllegalArgumentException> {
            resolver(null).store(store, SupabaseConfig("https://mine.example", ""))
        }
        assertTrueEmpty(store)
    }

    @Test
    fun `clearing forgets the stored value so the fallback applies again`() = runTest {
        val store = MapStore()
        val resolver = resolver(devConfig)
        resolver.store(store, SupabaseConfig("https://mine.example", "mine-key"))

        resolver.clear(store)

        // Sign-out calls this. Leaving another account's project configured on a
        // shared device is the kind of thing nobody remembers to clean up.
        assertEquals("https://dev.example", resolver.resolve(store)?.url)
    }

    @Test
    fun `a config with a blank url cannot be constructed`() = runTest {
        assertFailsWith<IllegalArgumentException> { SupabaseConfig(url = "", anonKey = "k") }
        assertFailsWith<IllegalArgumentException> { SupabaseConfig(url = "https://x", anonKey = " ") }
    }

    private fun assertTrueEmpty(store: MapStore) =
        assertEquals(emptyMap(), store.values, "a refused write must leave the store untouched")
}
