package com.singularity.todo.core.search

import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

/**
 * The word-prefix rule of the search — the semantics the full-text index
 * implements.
 *
 * The matcher and the index have to agree: the index produces the candidates,
 * this matcher decides among them. If the two disagreed, a record could be
 * found by one and not by the other, and the search would answer a question
 * nobody asked.
 */
@Tag("fast")
class TextMatchTest {

    companion object {
        @JvmStatic
        fun matchesCases(): List<Arguments> = listOf(
            // ── Word prefix, not "contains" ──────────────────────────────────
            Arguments.of("Write the quarterly report", "quarterly", true),
            Arguments.of("Write the quarterly report", "quarter", true),
            Arguments.of("Write the quarterly report", "quar", true),
            Arguments.of("airport logistics", "port", false),
            // ── Case folding ─────────────────────────────────────────────────
            Arguments.of("Quarterly Report", "quarterly", true),
            Arguments.of("quarterly report", "QUARTERLY", true),
            // ── Phrases ──────────────────────────────────────────────────────
            Arguments.of("Write the quarterly report today", "quarterly report", true),
            Arguments.of("Write the report of the quarter", "quarterly report", false),
            // Only the last word of a phrase may be unfinished.
            Arguments.of("Write the quarterly report today", "quarterly rep", true),
            Arguments.of("Write the quarterly report today", "quarterly quar", false),
            // ── Degenerate terms and text ────────────────────────────────────
            Arguments.of("Buy milk", "!!!", false),
            Arguments.of("Buy milk", "   ", false),
            Arguments.of("", "milk", false),
            Arguments.of(null, "milk", false),
            // ── Non-ASCII is tokenized like the index does ───────────────────
            Arguments.of("Купить молоко", "мол", true),
            Arguments.of("Купить молоко", "МОЛОКО", true),
            // ── Diacritics fold the way the index folds them ─────────────────
            Arguments.of("Café au lait", "cafe", true),
            Arguments.of("café au lait", "café", true),
            Arguments.of("Café au lait", "CAFÉ", true),
            // "ß" is not "ss": a character folds to one base letter or not at all.
            Arguments.of("straße", "strasse", false),
            Arguments.of("straße", "straße", true),
        )

        @JvmStatic
        fun containsCases(): List<Arguments> = listOf(
            Arguments.of("Café au lait", "cafe", true),
            Arguments.of("Café au lait", "Lait", true),
            Arguments.of("Café au lait", "milk", false),
        )

        @JvmStatic
        fun matchesAnyCases(): List<Arguments> = listOf(
            Arguments.of(listOf("Title", "Description"), "desc", true),
            Arguments.of(listOf("Title", "Description"), "missing", false),
        )

        @JvmStatic
        fun tokensCases(): List<Arguments> = listOf(
            Arguments.of("Buy milk!", listOf("buy", "milk")),
            Arguments.of("task42_x", listOf("task42", "x")),
            Arguments.of("---", emptyList<String>()),
        )
    }

    @ParameterizedTest
    @MethodSource("matchesCases")
    fun `matches applies the word-prefix rule`(text: String?, term: String, expected: Boolean) {
        assertEquals(expected, TextMatch.matches(text, term), "matches($text, $term)")
    }

    @ParameterizedTest
    @MethodSource("containsCases")
    fun `contains folds diacritics in both modes`(text: String, term: String, expected: Boolean) {
        assertEquals(expected, TextMatch.contains(text, term), "contains($text, $term)")
    }

    @ParameterizedTest
    @MethodSource("matchesAnyCases")
    fun `matchesAny answers for a choice of surfaces`(surfaces: List<String>, term: String, expected: Boolean) {
        assertEquals(expected, TextMatch.matchesAny(surfaces, term))
    }

    @ParameterizedTest
    @MethodSource("tokensCases")
    fun `tokens are runs of letters and digits`(input: String, expected: List<String>) {
        assertEquals(expected, TextMatch.tokens(input))
    }
}
