package com.singularity.todo.core.linkpreview

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cache answers without a fetch, forgets one address at a time, and expires.
 *
 * The clock is the injected one, so "six hours later" is a value rather than a wait.
 */
@Tag("fast")
class LinkPreviewCacheTest {
    /** The smallest DataStore a test can use: one mutable preferences map. */
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private val clock = FakeClock(kotlin.time.Instant.parse("2026-01-01T00:00:00Z"))

    private fun cache(
        store: DataStore<Preferences> = MemoryPreferences(),
        files: MapFileSystem = MapFileSystem(),
    ) = LinkPreviewCache(store, files, "/previews", clock)

    private fun preview(
        url: String,
        at: Long = 0L,
    ) = LinkPreview(
        url = url,
        siteName = "Example",
        title = "A page",
        description = "What it is",
        imageUrl = null,
        fetchedAt = at,
    )

    @Test
    fun `a stored preview comes back`() =
        runTest {
            val cache = cache()
            cache.put(preview("https://example.com/a"))
            assertEquals("A page", cache.cached("https://example.com/a")?.title)
        }

    @Test
    fun `a missing address has no entry`() =
        runTest {
            assertNull(cache().cached("https://example.com/none"))
        }

    @Test
    fun `the entry is keyed by the normalised address`() =
        runTest {
            val cache = cache()
            cache.put(preview("https://example.com/a#top"))
            assertEquals("A page", cache.cached(PreviewUrls.keyOf("HTTPS://Example.com/a"))?.title)
        }

    @Test
    fun `an entry older than the refresh window is not fresh`() =
        runTest {
            val cache = cache()
            val now = clock.now().toEpochMilliseconds()
            cache.put(preview("https://example.com/a", at = now - LinkPreviewCache.REFRESH_AFTER_MILLIS - 1))
            assertFalse(cache.isFresh("https://example.com/a"))
        }

    @Test
    fun `a recent entry is fresh, which is what stops a refetch on every opening`() =
        runTest {
            val cache = cache()
            cache.put(preview("https://example.com/a", at = clock.now().toEpochMilliseconds()))
            assertTrue(cache.isFresh("https://example.com/a"))
        }

    @Test
    fun `hiding is remembered against the address`() =
        runTest {
            val cache = cache()
            cache.hide("https://example.com/a")
            assertTrue(cache.observeHidden("https://example.com/a").first())
            assertFalse(cache.observeHidden("https://example.com/b").first())
        }

    @Test
    fun `forgetting removes the entry and its image`() =
        runTest {
            val files = MapFileSystem()
            val cache = cache(files = files)
            cache.put(preview("https://example.com/a"))
            cache.putImage("https://example.com/a", byteArrayOf(1, 2, 3))
            assertTrue(cache.imagePathOrNull("https://example.com/a") != null)

            cache.forget("https://example.com/a")

            assertNull(cache.cached("https://example.com/a"))
            assertNull(cache.imagePathOrNull("https://example.com/a"))
        }

    @Test
    fun `forgetting one address leaves another one alone`() =
        runTest {
            val cache = cache()
            cache.put(preview("https://example.com/a"))
            cache.put(preview("https://example.com/b"))
            cache.forget("https://example.com/a")
            assertEquals("A page", cache.cached("https://example.com/b")?.title)
        }

    @Test
    fun `an image is stored under the address and read back`() =
        runTest {
            val cache = cache()
            val path = cache.putImage("https://example.com/a", byteArrayOf(9, 9))
            assertTrue(path.startsWith("/previews/"))
            assertEquals(path, cache.imagePathOrNull("https://example.com/a"))
        }

    @Test
    fun `the cache keeps its bound by dropping the oldest address`() =
        runTest {
            val cache = cache()
            repeat(LinkPreviewCache.MAX_ENTRIES) { index ->
                cache.put(preview("https://example.com/$index", at = index.toLong()))
            }
            val evicted = cache.put(preview("https://example.com/new", at = 10_000L)).evicted
            assertEquals(1, evicted)
            assertNull(cache.cached("https://example.com/0"))
            assertEquals("A page", cache.cached("https://example.com/new")?.title)
        }

    @Test
    fun `a cache under the bound evicts nothing`() =
        runTest {
            val cache = cache()
            assertEquals(0, cache.put(preview("https://example.com/a")).evicted)
        }

    @Test
    fun `a stored preview survives a store that also carries unrelated settings`() =
        runTest {
            val store = MemoryPreferences()
            store.edit { it[booleanPreferencesKey("tasks.link_previews_enabled")] = true }
            val cache = cache(store)
            cache.put(preview("https://example.com/a"))
            assertEquals("A page", cache.cached("https://example.com/a")?.title)
        }

    @Test
    fun `an oversized field is cut to what the card holds`() =
        runTest {
            val cache = cache()
            val huge = "x".repeat(10_000)
            cache.put(
                LinkPreview(
                    url = "https://example.com/a",
                    siteName = huge,
                    title = huge,
                    description = huge,
                    imageUrl = "https://example.com/$huge",
                    fetchedAt = 0L,
                ),
            )
            val stored = cache.cached("https://example.com/a")!!
            assertEquals(LinkPreviewCache.MAX_TITLE_CHARS, stored.title.length)
            assertEquals(LinkPreviewCache.MAX_SITE_CHARS, stored.siteName!!.length)
            assertEquals(LinkPreviewCache.MAX_DESCRIPTION_CHARS, stored.description!!.length)
            assertEquals(LinkPreviewCache.MAX_URL_CHARS, stored.imageUrl!!.length)
        }

    @Test
    fun `an image is dropped when a refresh names a different one`() =
        runTest {
            val files = MapFileSystem()
            val cache = cache(files = files)
            cache.put(preview("https://example.com/a").copy(imageUrl = "https://example.com/one.png"))
            cache.putImage("https://example.com/a", byteArrayOf(1, 2, 3))
            assertTrue(cache.imagePathOrNull("https://example.com/a") != null)

            // The page now names a different picture: the old file must not survive to be
            // shown beside the new title.
            cache.put(preview("https://example.com/a").copy(imageUrl = "https://example.com/two.png"))

            assertNull(cache.imagePathOrNull("https://example.com/a"))
        }

    @Test
    fun `an image survives a refresh that names the same one`() =
        runTest {
            val files = MapFileSystem()
            val cache = cache(files = files)
            val entry = preview("https://example.com/a").copy(imageUrl = "https://example.com/one.png")
            cache.put(entry)
            cache.putImage("https://example.com/a", byteArrayOf(1))
            cache.put(entry)
            assertTrue(cache.imagePathOrNull("https://example.com/a") != null)
        }

    @Test
    fun `dismissals are bounded like entries`() =
        runTest {
            val cache = cache()
            repeat(LinkPreviewCache.MAX_ENTRIES + 5) { index ->
                cache.hide("https://example.com/$index")
            }
            // The most recent dismissals are the ones a person is still living with.
            assertTrue(cache.observeHidden("https://example.com/${LinkPreviewCache.MAX_ENTRIES + 4}").first())
        }

    @Test
    fun `an evicted entry takes its image with it`() =
        runTest {
            val files = MapFileSystem()
            val cache = cache(files = files)
            // A non-normalised address on purpose: the entry's key is not the string the
            // preview carries, and the first version of the eviction path passed the
            // address where a key was expected — so every evicted file leaked.
            repeat(LinkPreviewCache.MAX_ENTRIES) { index ->
                cache.put(preview("HTTPS://Example.COM/$index", at = index.toLong()))
            }
            val oldest = PreviewUrls.keyOf("HTTPS://Example.COM/0")
            cache.putImage(oldest, byteArrayOf(1))
            assertTrue(cache.imagePathOrNull(oldest) != null)

            val outcome = cache.put(preview("HTTPS://Example.COM/new", at = 10_000L))

            assertEquals(1, outcome.evicted)
            assertNull(
                cache.imagePathOrNull(oldest),
                "the image of an evicted entry must not survive it — an orphan file is shown as current",
            )
        }

    @Test
    fun `a missing image file is not an error`() =
        runTest {
            val cache = cache()
            // No image was ever written: forgetting the entry must still succeed, because
            // a preview with no picture is the ordinary case.
            cache.put(preview("https://example.com/a"))
            cache.forget(PreviewUrls.keyOf("https://example.com/a"))
            assertNull(cache.cached("https://example.com/a"))
        }
}
