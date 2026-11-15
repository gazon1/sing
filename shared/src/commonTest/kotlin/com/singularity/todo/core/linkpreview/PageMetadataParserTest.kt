package com.singularity.todo.core.linkpreview

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the metadata reader takes from a page, and what it refuses to invent.
 *
 * The fixtures are the shapes real pages publish: Open Graph alone, a plain HTML head,
 * a page where the two disagree, and a page that says nothing at all.
 */
@Tag("fast")
class PageMetadataParserTest {
    @Test
    fun `open graph wins over the plain html head`() {
        val html =
            """
            <html><head>
              <title>Plain title</title>
              <meta name="description" content="Plain description">
              <meta property="og:title" content="Graph title">
              <meta property="og:description" content="Graph description">
              <meta property="og:site_name" content="Example News">
              <meta property="og:image" content="https://cdn.example.com/cover.png">
            </head></html>
            """.trimIndent()

        val meta = PageMetadataParser.parse(html, "https://example.com/post")
        assertEquals("Graph title", meta.title)
        assertEquals("Graph description", meta.description)
        assertEquals("Example News", meta.siteName)
        assertEquals("https://cdn.example.com/cover.png", meta.imageUrl)
    }

    @Test
    fun `a plain page falls back to the document title and the description meta`() {
        val html =
            """
            <html><head>
              <title>  A   plain page </title>
              <meta name="description" content="What it is about">
            </head></html>
            """.trimIndent()

        val meta = PageMetadataParser.parse(html, "https://example.com/post")
        assertEquals("A plain page", meta.title)
        assertEquals("What it is about", meta.description)
        assertNull(meta.siteName)
        assertNull(meta.imageUrl)
    }

    @Test
    fun `a relative image is resolved against the page that was served`() {
        val html = """<meta property="og:image" content="/img/cover.png">"""
        val meta = PageMetadataParser.parse(html, "https://example.com/post/1")
        assertEquals("https://example.com/img/cover.png", meta.imageUrl)
    }

    @Test
    fun `a canonical link is what relative addresses resolve against`() {
        val html =
            """
            <link rel="canonical" href="https://canonical.example.org/article/">
            <meta property="og:image" content="cover.png">
            """.trimIndent()

        val meta = PageMetadataParser.parse(html, "https://example.com/redirected")
        assertEquals("https://canonical.example.org/article/cover.png", meta.imageUrl)
    }

    @Test
    fun `entities in a title are decoded`() {
        val html = """<title>Fish &amp; chips &#39;n&#39; peas</title>"""
        assertEquals("Fish & chips 'n' peas", PageMetadataParser.parse(html, "https://e.com/").title)
    }

    @Test
    fun `a page with no metadata yields nothing rather than guesses`() {
        val meta = PageMetadataParser.parse("<html><body>Hello</body></html>", "https://e.com/")
        assertNull(meta.title)
        assertNull(meta.description)
        assertNull(meta.siteName)
        assertNull(meta.imageUrl)
    }

    @Test
    fun `a repeated key keeps the first value, which is what the page stated first`() {
        val html =
            """
            <meta property="og:title" content="First">
            <meta property="og:title" content="Second">
            """.trimIndent()
        assertEquals("First", PageMetadataParser.parse(html, "https://e.com/").title)
    }

    @Test
    fun `an empty content attribute is not a value`() {
        val html = """<meta property="og:description" content="  ">"""
        assertNull(PageMetadataParser.parse(html, "https://e.com/").description)
    }

    @Test
    fun `single-quoted attributes are read like double-quoted ones`() {
        val html = """<meta property='og:title' content='Quoted'>"""
        assertEquals("Quoted", PageMetadataParser.parse(html, "https://e.com/").title)
    }
}
