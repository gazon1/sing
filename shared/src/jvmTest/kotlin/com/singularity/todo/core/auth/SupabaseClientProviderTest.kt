package com.singularity.todo.core.auth

import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The client cache has to remember the configuration it built the client for.
 *
 * ## What the bug looked like from the outside
 *
 * The cache held the client but not the configuration, so the comparison that
 * decides "is this client still the right one?" never matched and every call
 * built a new client. The visible symptom was not slowness: the session lives
 * inside the client's `Auth` plugin, so a sign-in filled the plugin of the
 * client it ran against, and the next request — made through a different
 * instance — found an empty plugin and fell back to the anon key.
 *
 * A signed-in user whose requests arrive as a stranger is a confusing failure:
 * the app says "signed in", the server says "who is this?". For the backup
 * feature it was a row-level security policy refusing an upload the user was
 * entitled to make.
 *
 * ## Why these tests are about identity and not about requests
 *
 * A test that asserts "the request carries the user's token" needs a server. The
 * property that makes that true is one line long: the client handed out is the
 * client that was signed in on. Identity is checkable here, with no network.
 */
@Tag("fast")
class SupabaseClientProviderTest {

    @Test
    fun `the same configuration yields the same client`() = runTest {
        val provider = providerWithStoredConfig()

        val first = assertNotNull(provider.client())
        val second = assertNotNull(provider.client())

        assertSame(
            first,
            second,
            "two calls for the same configuration handed out two clients, so the " +
                "session signed in on the first one is unreachable from the second",
        )

        provider.forget()
    }

    @Test
    fun `the session carrier is the same object across calls`() = runTest {
        val provider = providerWithStoredConfig()

        val first = assertNotNull(provider.client())
        val second = assertNotNull(provider.client())

        assertSame(
            first.auth,
            second.auth,
            "the auth plugin is where the session lives; two plugins mean two sessions",
        )

        provider.forget()
    }

    @Test
    fun `a configured project is the client that is handed out`() = runTest {
        val storage = MapSecureStorage()
        val provider = provider(storage)

        provider.configure(CONFIG)

        assertSame(
            assertNotNull(provider.client()),
            provider.client(),
            "the client built for the configuration must be the one the cache keeps",
        )

        provider.forget()
    }

    @Test
    fun `a changed configuration replaces the client`() = runTest {
        val storage = MapSecureStorage()
        val provider = provider(storage)
        provider.configure(CONFIG)
        val before = assertNotNull(provider.client())

        provider.configure(CONFIG.copy(url = "https://other.supabase.co"))

        assertNotSame(
            before,
            provider.client(),
            "a client pointing at the previous project must not answer for the new one",
        )

        provider.forget()
    }

    @Test
    fun `a client is not built when nothing is configured`() = runTest {
        val provider = provider(MapSecureStorage())

        assertNull(provider.client(), "an unconfigured app has no client to hand out")
    }

    private fun provider(storage: MapSecureStorage) = SupabaseClientProvider(
        resolver = SupabaseConfigResolver(idGenerator = FixedIdGenerator(), buildTime = { null }),
        store = storage,
    )

    /**
     * A provider whose configuration was written straight into the store.
     *
     * The store is the path that matters: the configuration is read back on every
     * call, so the client has to be recognised by *that* value. A test that went
     * through `configure` would only prove the cache that `configure` filled in.
     */
    private suspend fun providerWithStoredConfig(): SupabaseClientProvider {
        val storage = MapSecureStorage()
        storage.write(SupabaseConfigResolver.KEY_URL, CONFIG.url)
        storage.write(SupabaseConfigResolver.KEY_ANON_KEY, CONFIG.anonKey)
        return provider(storage)
    }

    private companion object {
        /**
         * A syntactically valid JWT-shaped anon key.
         *
         * The SDK inspects the key's shape and warns when it cannot recognise it;
         * the warning is noise in a test that is not about the key.
         */
        const val ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiJ9.c2lnbmF0dXJl"
        val CONFIG = SupabaseConfig(url = "https://example.supabase.co", anonKey = ANON_KEY)
    }
}
