package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.search.query.QueryTokenizer.Token
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryTokenizerTest {

    private fun tokens(input: String): List<Token> = QueryTokenizer(input).tokens()

    @Test
    fun `empty input produces empty list`() {
        assertEquals(emptyList(), tokens(""))
        assertEquals(emptyList(), tokens("   "))
    }

    @Test
    fun `single word tokenized correctly`() {
        assertEquals(listOf(Token.Word("hello")), tokens("hello"))
    }

    @Test
    fun `multiple words separated by spaces`() {
        assertEquals(
            listOf(Token.Word("state:done"), Token.Word("priority:A"), Token.Word("tag:work")),
            tokens("state:done priority:A tag:work"),
        )
    }

    @Test
    fun `AND OR NOT recognized as keywords`() {
        assertEquals(listOf(Token.And), tokens("AND"))
        assertEquals(listOf(Token.And), tokens("and"))
        assertEquals(listOf(Token.Or), tokens("OR"))
        assertEquals(listOf(Token.Or), tokens("or"))
        assertEquals(listOf(Token.Not), tokens("NOT"))
        assertEquals(listOf(Token.Not), tokens("not"))
    }

    @Test
    fun `parentheses tokenized`() {
        assertEquals(listOf(Token.LParen), tokens("("))
        assertEquals(listOf(Token.RParen), tokens(")"))
        assertEquals(
            listOf(Token.LParen, Token.Word("a"), Token.Or, Token.Word("b"), Token.RParen),
            tokens("( a or b )"),
        )
    }

    @Test
    fun `quoted string extracts content`() {
        assertEquals(listOf(Token.Quoted("hello world")), tokens("\"hello world\""))
        assertEquals(listOf(Token.Quoted("foo bar baz")), tokens("\"foo bar baz\""))
    }

    @Test
    fun `quoted string handles escape sequences`() {
        assertEquals(listOf(Token.Quoted("a\"b")), tokens("\"a\\\"b\""))
        assertEquals(listOf(Token.Quoted("x\\y")), tokens("\"x\\\\y\""))
    }

    @Test
    fun `mixed operators and words`() {
        assertEquals(
            listOf(
                Token.Word("state:done"),
                Token.And,
                Token.Not,
                Token.Word("tag:work"),
            ),
            tokens("state:done AND NOT tag:work"),
        )
    }

    @Test
    fun `complex query tokenized`() {
        assertEquals(
            listOf(
                Token.Word("priority:high"),
                Token.And,
                Token.LParen,
                Token.Word("tag:work"),
                Token.Or,
                Token.Word("tag:urgent"),
                Token.RParen,
                Token.And,
                Token.Word("due:today"),
            ),
            tokens("priority:high AND ( tag:work OR tag:urgent ) AND due:today"),
        )
    }

    @Test
    fun `bare words containing special chars are preserved`() {
        assertEquals(listOf(Token.Word("hello-world")), tokens("hello-world"))
        assertEquals(listOf(Token.Word("foo@bar.com")), tokens("foo@bar.com"))
        assertEquals(listOf(Token.Word("a+b")), tokens("a+b"))
    }

    @Test
    fun `whitespace-only tokens skipped`() {
        assertEquals(listOf(Token.Word("a")), tokens("  a  "))
        assertEquals(
            listOf(Token.Word("a"), Token.And, Token.Word("b")),
            tokens("  a   AND   b  "),
        )
    }

    @Test
    fun `empty quoted string`() {
        assertEquals(listOf(Token.Quoted("")), tokens("\"\""))
    }

    @Test
    fun `AND OR NOT inside quoted are literal`() {
        assertEquals(listOf(Token.Quoted("AND")), tokens("\"AND\""))
        assertEquals(listOf(Token.Quoted("OR")), tokens("\"OR\""))
    }
}
