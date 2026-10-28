package com.singularity.todo.core.ui.text

import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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

    /** Every detected link as its URL, whether web or internal. */
    private fun allUrls(text: String): List<String> =
        TextLinkDetector.detect(text).map { span ->
            (span.link as? TextLink.Web)?.url ?: (span.link as TextLink.Internal).target.url
        }

    companion object {
        @JvmStatic
        fun webCases(): List<Arguments> = listOf(
            Arguments.of("https://example.com", listOf("https://example.com")),
            Arguments.of("http://example.com", listOf("http://example.com")),
            Arguments.of("www.example.com", listOf("https://www.example.com")),
            // ── Not links ────────────────────────────────────────────────────
            Arguments.of("3.14", emptyList<String>()),
            Arguments.of("2.5.6", emptyList<String>()),
            Arguments.of("file.txt", emptyList<String>()),
            Arguments.of("just a sentence", emptyList<String>()),
            Arguments.of("", emptyList<String>()),
            // ── Inside a sentence ────────────────────────────────────────────
            Arguments.of("see https://example.com for more", listOf("https://example.com")),
            // ── Trailing punctuation ─────────────────────────────────────────
            Arguments.of("https://example.com.", listOf("https://example.com")),
            Arguments.of("https://example.com, then more", listOf("https://example.com")),
            // A bracket the address itself opened stays.
            Arguments.of(
                "https://en.wikipedia.org/wiki/Foo_(bar)",
                listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            ),
            // A bracket the sentence opened, or one with no partner, is dropped.
            Arguments.of("(https://example.com)", listOf("https://example.com")),
            Arguments.of("https://example.com)", listOf("https://example.com")),
            Arguments.of("https://example.com!", listOf("https://example.com")),
            Arguments.of("https://example.com;", listOf("https://example.com")),
            Arguments.of("https://example.com:", listOf("https://example.com")),
            Arguments.of("https://example.com?", listOf("https://example.com")),
        )

        @JvmStatic
        fun internalCases(): List<Arguments> = listOf(
            Arguments.of("note://abc", listOf("note://abc")),
            Arguments.of("task://xyz", listOf("task://xyz")),
            // A foreign scheme is not one of ours.
            Arguments.of("xnote://abc", emptyList<String>()),
            Arguments.of("notask://abc", emptyList<String>()),
            // The scheme is matched as the app writes it.
            Arguments.of("NOTE://abc", emptyList<String>()),
            Arguments.of("Note://abc", emptyList<String>()),
        )

        @JvmStatic
        fun structureCases(): List<Arguments> = listOf(
            Arguments.of(
                "see https://example.com and note://abc and task://xyz",
                listOf("https://example.com", "note://abc", "task://xyz"),
            ),
            Arguments.of(
                "https://example.com/x note://abc",
                listOf("https://example.com/x", "note://abc"),
            ),
            // A web address that contains something link-shaped stays one link.
            Arguments.of(
                "https://example.com/?ref=note://abc",
                listOf("https://example.com/?ref=note://abc"),
            ),
            Arguments.of("go note://abc now", listOf("note://abc")),
        )
    }

    @ParameterizedTest
    @MethodSource("webCases")
    fun `web links are detected with the right bounds`(text: String, expected: List<String>) {
        assertEquals(expected, webUrls(text))
    }

    @ParameterizedTest
    @MethodSource("internalCases")
    fun `internal links are detected only for the app's own schemes`(text: String, expected: List<String>) {
        assertEquals(expected, internalUrls(text))
    }

    @ParameterizedTest
    @MethodSource("structureCases")
    fun `several links are ordered, disjoint and cover exactly their text`(text: String, expected: List<String>) {
        val links = TextLinkDetector.detect(text)
        assertEquals(expected, allUrls(text))
        assertEquals(expected.size, links.size)
        links.forEachIndexed { index, span ->
            assertEquals(
                expected[index],
                text.substring(span.range.first, span.range.last + 1),
                "span $index must cover exactly its link",
            )
            if (index > 0) {
                assertTrue(links[index - 1].range.last < span.range.first, "spans must not overlap")
            }
        }
    }
}
