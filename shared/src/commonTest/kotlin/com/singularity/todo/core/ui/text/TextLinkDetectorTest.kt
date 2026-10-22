package com.singularity.todo.core.ui.text

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What counts as a link in a piece of text.
 *
 * The cases here are the ones people actually hit: a sentence that ends with a
 * full stop, a link inside brackets, two links on one line, a version number that
 * looks like an address, and a scheme that only looks like one of ours.
 */
@Tag("fast")
class TextLinkDetectorTest {

    private fun webUrls(text: String): List<String> =
        TextLinkDetector.detect(text).mapNotNull { (it.link as? TextLink.Web)?.url }

    private fun internalUrls(text: String): List<String> =
        TextLinkDetector.detect(text).mapNotNull { (it.link as? TextLink.Internal)?.target?.url }

    // ─── web links ───────────────────────────────────────────────────────────

    @Test
    fun `finds an https link`() {
        assertEquals(listOf("https://example.com"), webUrls("https://example.com"))
    }

    @Test
    fun `finds an http link`() {
        assertEquals(listOf("http://example.com"), webUrls("http://example.com"))
    }

    @Test
    fun `finds a www link and gives it a scheme`() {
        assertEquals(listOf("https://www.example.com"), webUrls("www.example.com"))
    }

    @Test
    fun `finds nothing in a version number`() {
        assertTrue(TextLinkDetector.detect("3.14").isEmpty())
        assertTrue(TextLinkDetector.detect("2.5.6").isEmpty())
    }

    @Test
    fun `finds nothing in a file name`() {
        assertTrue(TextLinkDetector.detect("file.txt").isEmpty())
    }

    @Test
    fun `finds a link inside a sentence`() {
        assertEquals(listOf("https://example.com"), webUrls("see https://example.com for more"))
    }

    // ─── trailing punctuation ─────────────────────────────────────────────────

    @Test
    fun `drops the full stop that ends the sentence`() {
        assertEquals(listOf("https://example.com"), webUrls("https://example.com."))
    }

    @Test
    fun `drops the comma that ends the clause`() {
        assertEquals(listOf("https://example.com"), webUrls("https://example.com, then more"))
    }

    @Test
    fun `keeps a bracket that the address itself opened`() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            webUrls("https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
    }

    @Test
    fun `drops a bracket that the sentence opened`() {
        assertEquals(listOf("https://example.com"), webUrls("(https://example.com)"))
    }

    @Test
    fun `drops a bracket that has no partner`() {
        assertEquals(listOf("https://example.com"), webUrls("https://example.com)"))
    }

    @Test
    fun `drops trailing punctuation that is not a bracket`() {
        assertEquals(listOf("https://example.com"), webUrls("https://example.com!"))
        assertEquals(listOf("https://example.com"), webUrls("https://example.com;"))
        assertEquals(listOf("https://example.com"), webUrls("https://example.com:"))
        assertEquals(listOf("https://example.com"), webUrls("https://example.com?"))
    }

    // ─── internal links ──────────────────────────────────────────────────────

    @Test
    fun `finds an internal note link`() {
        assertEquals(listOf("note://abc"), internalUrls("note://abc"))
    }

    @Test
    fun `finds an internal task link`() {
        assertEquals(listOf("task://xyz"), internalUrls("task://xyz"))
    }

    @Test
    fun `does not take a foreign scheme for one of ours`() {
        assertTrue(internalUrls("xnote://abc").isEmpty())
        assertTrue(internalUrls("notask://abc").isEmpty())
    }

    @Test
    fun `the scheme is matched as the app writes it`() {
        assertTrue(internalUrls("NOTE://abc").isEmpty())
        assertTrue(internalUrls("Note://abc").isEmpty())
    }

    // ─── several links ───────────────────────────────────────────────────────

    @Test
    fun `finds several links in one line`() {
        val text = "see https://example.com and note://abc and task://xyz"
        val links = TextLinkDetector.detect(text)
        assertEquals(3, links.size)
        assertEquals(
            listOf("https://example.com", "note://abc", "task://xyz"),
            links.map { it.link.let { l -> (l as? TextLink.Web)?.url ?: (l as TextLink.Internal).target.url } },
        )
    }

    @Test
    fun `the links do not overlap`() {
        val links = TextLinkDetector.detect("https://example.com/x note://abc")
        assertEquals(2, links.size)
        assertTrue(links[0].range.last < links[1].range.first)
    }

    @Test
    fun `a web address that contains something link-shaped is one link`() {
        val links = TextLinkDetector.detect("https://example.com/?ref=note://abc")
        assertEquals(1, links.size)
        assertEquals("https://example.com/?ref=note://abc", (links.single().link as TextLink.Web).url)
    }

    @Test
    fun `the range of a link covers the link and nothing else`() {
        val text = "go note://abc now"
        val span = TextLinkDetector.detect(text).single()
        assertEquals("note://abc", text.substring(span.range.first, span.range.last + 1))
    }

    @Test
    fun `finds nothing in plain text`() {
        assertTrue(TextLinkDetector.detect("just a sentence").isEmpty())
        assertTrue(TextLinkDetector.detect("").isEmpty())
    }
}
