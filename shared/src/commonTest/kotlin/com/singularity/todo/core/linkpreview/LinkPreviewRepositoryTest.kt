package com.singularity.todo.core.linkpreview

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.MapFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the screen gets, and what the network is asked for.
 *
 * The page source is a fake, so the tests can say both "what came back" and "how many
 * times was it asked" — the second is the caching requirement, and it is the one a
 * recomposition would break silently.
 */
@Tag("fast")
class LinkPreviewRepositoryTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    /** Answers pages from a map, counts the calls, and can be made to fail. */
    private class FakeSource(
        private val pages: Map<String, String> = emptyMap(),
        private val images: Map<String, ByteArray> = emptyMap(),
    ) : PageSource {
        val requested = mutableListOf<String>()
        var failWith: PreviewFetchException? = null

        override suspend fun fetch(
            url: String,
            maxBytes: Int,
            kind: FetchKind,
        ): FetchedPage {
            requested += url
            failWith?.let { throw it }
            val bytes =
                when (kind) {
                    FetchKind.Html -> pages[url]?.encodeToByteArray()
                    FetchKind.Image -> images[url]
                }
            if (bytes == null) throw PreviewFetchException("no ${kind.name.lowercase()} at $url")
            return FetchedPage(
                finalUrl = url,
                bytes = bytes,
                contentType = if (kind == FetchKind.Html) "text/html" else "image/png",
            )
        }
    }

    private val clock = FakeClock(kotlin.time.Instant.parse("2026-02-01T00:00:00Z"))
    private val url = "https://example.com/post"
    private val html =
        """
        <meta property="og:title" content="The page title">
        <meta property="og:description" content="The page description">
        <meta property="og:site_name" content="Example">
        <meta property="og:image" content="https://example.com/cover.png">
        """.trimIndent()

    private fun repository(
        source: PageSource,
        enabled: Boolean = true,
        cache: LinkPreviewCache = LinkPreviewCache(MemoryPreferences(), MapFileSystem(), "/previews", clock),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = LinkPreviewRepositoryImpl(
        source = source,
        cache = cache,
        enabled = MutableStateFlow(enabled),
        clock = clock,
        dispatcher = dispatcher,
    )

    @Test
    fun `the page's metadata becomes the preview`() =
        runTest {
            val state = repository(FakeSource(mapOf(url to html))).refresh(url)
            val ready = state as LinkPreviewState.Ready
            assertEquals("The page title", ready.preview.displayTitle)
            assertEquals("Example", ready.preview.siteName)
            assertEquals("The page description", ready.preview.description)
        }

    @Test
    fun `a second refresh within the window does not fetch again`() =
        runTest {
            val source = FakeSource(mapOf(url to html))
            val repo = repository(source)
            repo.refresh(url)
            repo.refresh(url)
            assertEquals(1, source.requested.count { it == url }, "a fresh entry must not cost a request")
        }

    @Test
    fun `the image is fetched through the guarded source, not handed to an image loader`() =
        runTest {
            val imageUrl = "https://example.com/cover.png"
            val source = FakeSource(mapOf(url to html), mapOf(imageUrl to byteArrayOf(1, 2, 3)))
            repository(source).refresh(url)
            assertTrue(imageUrl in source.requested)
        }

    @Test
    fun `a failed image does not cost the preview`() =
        runTest {
            val source = FakeSource(mapOf(url to html))
            val state = repository(source).refresh(url)
            assertTrue(state is LinkPreviewState.Ready, "a card without a picture is still a card")
        }

    @Test
    fun `a failed fetch is unavailable and the address stays in the title's hands`() =
        runTest {
            val source = FakeSource(mapOf(url to html))
            source.failWith = PreviewFetchException("offline")
            assertEquals(LinkPreviewState.Unavailable, repository(source).refresh(url))
        }

    @Test
    fun `a failed refetch keeps the preview that was already there`() =
        runTest {
            val source = FakeSource(mapOf(url to html))
            val cache = LinkPreviewCache(MemoryPreferences(), MapFileSystem(), "/previews", clock)
            val repo = repository(source, cache = cache)
            repo.refresh(url)

            // Six hours later the entry is stale; the site is now unreachable.
            clock.advance(kotlin.time.Duration.parse("7h"))
            source.failWith = PreviewFetchException("offline")

            val state = repo.refresh(url) as LinkPreviewState.Ready
            assertEquals("The page title", state.preview.title)
        }

    @Test
    fun `the toggle off means nothing is fetched at all`() =
        runTest {
            val source = FakeSource(mapOf(url to html))
            val repo = repository(source, enabled = false)
            assertEquals(LinkPreviewState.None, repo.refresh(url))
            assertTrue(source.requested.isEmpty(), "the toggle is read before the request, not after")
        }

    @Test
    fun `the toggle off makes an observation report nothing`() =
        runTest {
            val repo = repository(FakeSource(mapOf(url to html)), enabled = false)
            assertEquals(LinkPreviewState.None, repo.observe(url).first())
        }

    @Test
    fun `a hidden preview is hidden and not refetched`() =
        runTest {
            val source = FakeSource(mapOf(url to html))
            val repo = repository(source)
            repo.hide(url)
            assertEquals(LinkPreviewState.Hidden, repo.refresh(url))
            assertTrue(source.requested.isEmpty())
        }

    @Test
    fun `a title without an address observes nothing`() =
        runTest {
            assertEquals(LinkPreviewState.None, repository(FakeSource()).observe(null).first())
        }

    @Test
    fun `a page with no metadata still yields a preview titled by its host`() =
        runTest {
            val state = repository(FakeSource(mapOf(url to "<html></html>"))).refresh(url)
            val ready = state as LinkPreviewState.Ready
            assertEquals("example.com", ready.preview.displayTitle)
        }

    @Test
    fun `the source is asked for html, and a page failure surfaces as a failed fetch`() =
        runTest {
            val source = FakeSource()
            assertFailsWith<PreviewFetchException> { source.fetch(url, 1024, FetchKind.Html) }
        }

    @Test
    fun `two callers for the same address cost one fetch`() =
        runTest {
            val source = FakeSource(mapOf(url to html))
            // Both are started before either can finish: the second must wait for the
            // first, not fetch the page a second time. A `StandardTestDispatcher` keeps
            // them suspended until the scheduler runs them together — `Unconfined` would
            // finish the first before the second existed, and prove nothing.
            val gate = CompletableDeferred<Unit>()
            var pageFetches = 0
            val slow =
                object : PageSource {
                    override suspend fun fetch(
                        url: String,
                        maxBytes: Int,
                        kind: FetchKind,
                    ): FetchedPage {
                        // Count the page only: the picture is a second, legitimate fetch of
                        // a different address, and counting it would hide the bug this test
                        // is about.
                        if (kind == FetchKind.Html) {
                            pageFetches++
                            gate.await()
                        }
                        return source.fetch(url, maxBytes, kind)
                    }
                }
            val repo = repository(slow, dispatcher = StandardTestDispatcher(testScheduler))
            // The test body and the repository share one scheduler, so `runCurrent`
            // advances both and the two callers really are in flight together.
            val first = async { repo.refresh(url) }
            runCurrent()
            val second = async { repo.refresh(url) }
            runCurrent()
            gate.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, pageFetches, "the second caller must join the first fetch, not repeat it")
        }

    @Test
    fun `different addresses are fetched independently`() =
        runTest {
            val other = "https://example.org/other"
            val source = FakeSource(mapOf(url to html, other to html))
            val repo = repository(source)
            val a = async { repo.refresh(url) }
            val b = async { repo.refresh(other) }
            a.await()
            b.await()
            assertEquals(
                listOf(url, other).sorted(),
                source.requested.filter { it != "https://example.com/cover.png" }.sorted(),
            )
        }
}
