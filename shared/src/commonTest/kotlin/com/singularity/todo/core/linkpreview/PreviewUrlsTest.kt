package com.singularity.todo.core.linkpreview

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which address a title previews, and what it is cached under.
 *
 * The cases are the ones that actually occur: a link in a sentence, two links on one
 * line, an internal link beside a web one, and the same page reached with and without
 * a fragment.
 */
@Tag("fast")
class PreviewUrlsTest {
    @Test
    fun `the first web address in the title is the one previewed`() {
        assertEquals(
            "https://example.com/a",
            PreviewUrls.primaryIn("Read https://example.com/a and https://example.org/b"),
        )
    }

    @Test
    fun `a title with no address previews nothing`() {
        assertNull(PreviewUrls.primaryIn("Buy milk"))
    }

    @Test
    fun `an internal link is never previewed`() {
        assertNull(PreviewUrls.primaryIn("See task://abc for the details"))
    }

    @Test
    fun `an internal link before a web link does not hide the web link`() {
        assertEquals(
            "https://example.com/a",
            PreviewUrls.primaryIn("task://abc then https://example.com/a"),
        )
    }

    @Test
    fun `a www address is previewed as https`() {
        assertEquals("https://www.example.com/x", PreviewUrls.primaryIn("see www.example.com/x"))
    }

    @Test
    fun `the cache key drops the fragment and lowercases scheme and host`() {
        assertEquals(
            "https://example.com/a?b=1",
            PreviewUrls.keyOf("HTTPS://Example.COM/a?b=1#section"),
        )
    }

    @Test
    fun `the cache key keeps the query, because it names a different page`() {
        assertTrue(PreviewUrls.keyOf("https://e.com/?id=1") != PreviewUrls.keyOf("https://e.com/?id=2"))
    }

    @Test
    fun `the cache key keeps the path's case, because paths are case-sensitive`() {
        assertTrue(PreviewUrls.keyOf("https://e.com/A") != PreviewUrls.keyOf("https://e.com/a"))
    }

    @Test
    fun `the host is the authority without its port or credentials`() {
        assertEquals("example.com", PreviewUrls.host("https://user:secret@example.com:8443/x"))
        assertEquals("example.com", PreviewUrls.host("example.com/x"))
    }

    @Test
    fun `a relative image address is resolved against the page`() {
        // A relative reference keeps the page's directory: `https://example.com/post/1`
        // sits in `/post/`, so `img/a.png` is `/post/img/a.png` (RFC 3986 §5.2).
        assertEquals(
            "https://example.com/post/img/a.png",
            PreviewUrls.resolve("img/a.png", "https://example.com/post/1"),
        )
        assertEquals(
            "https://example.com/img/a.png",
            PreviewUrls.resolve("/img/a.png", "https://example.com/post/1"),
        )
        assertEquals(
            "https://cdn.example.com/a.png",
            PreviewUrls.resolve("//cdn.example.com/a.png", "https://example.com/post/1"),
        )
    }

    @Test
    fun `an absolute image address is kept as it is`() {
        assertEquals(
            "https://cdn.example.com/a.png",
            PreviewUrls.resolve("https://cdn.example.com/a.png", "https://example.com/"),
        )
    }

    @Test
    fun `a path that climbs is resolved, not concatenated`() {
        // `../a.png` from `/post/deep/1` steps out of `/post/deep/`, not out of the host.
        assertEquals(
            "https://example.com/post/a.png",
            PreviewUrls.resolve("../a.png", "https://example.com/post/deep/1"),
        )
        assertEquals(
            "https://example.com/a.png",
            PreviewUrls.resolve("../../a.png", "https://example.com/post/deep/1"),
        )
    }

    @Test
    fun `a query-only reference keeps the page's path`() {
        assertEquals("https://example.com/p?a=1", PreviewUrls.resolve("?a=1", "https://example.com/p"))
    }

    @Test
    fun `a fragment-only reference keeps the page's path`() {
        assertEquals("https://example.com/p#top", PreviewUrls.resolve("#top", "https://example.com/p"))
    }

    @Test
    fun `the host of an address with credentials is the real host`() {
        // `https://evil.com@127.0.0.1/` connects to 127.0.0.1, so the SSRF check must
        // judge 127.0.0.1 — which is what `host` returns.
        assertEquals("127.0.0.1", PreviewUrls.host("https://evil.com@127.0.0.1/x"))
    }

    @Test
    fun `a blank reference resolves to nothing`() {
        assertNull(PreviewUrls.resolve("   ", "https://example.com/"))
    }
}
