package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin lexer's own rules, pinned case by case.
 *
 * The cross-language contract lives in
 * `config/test-fixtures/kotlin-brace-matching.txt` and is checked by
 * [KotlinTextFixtureTest]. This file pins the rules themselves — the shapes the
 * two languages must both get right, and the two they must both get *fast*: an
 * unbalanced quote must cost a wrong answer, never a wedged gate.
 *
 * The sources are written as ordinary strings with escaped quotes on purpose:
 * a raw string cannot contain the three-quote marker, and the shapes under
 * test are exactly the ones that have it.
 */
@Tag("fast")
class KotlinTextTest {

    @Test
    fun a_brace_inside_a_string_is_not_a_brace() {
        assertTrue('{' !in KotlinText.stripCode("val a = \"{\""))
    }

    @Test
    fun a_brace_inside_a_raw_string_is_not_a_brace() {
        assertTrue('{' !in KotlinText.stripCode("val a = \"\"\"{\"\"\""))
    }

    @Test
    fun a_brace_inside_a_kdoc_is_not_a_brace() {
        assertTrue('{' !in KotlinText.stripCode("/* { */ val a = 1"))
    }

    @Test
    fun a_brace_inside_a_char_literal_is_not_a_brace() {
        assertTrue('}' !in KotlinText.stripCode("val a = '}'"))
    }

    @Test
    fun a_template_body_is_code_and_its_delimiters_are_not() {
        val stripped = KotlinText.stripCode("val s = \"\${run { 1 }}\"")
        assertTrue("{ 1 }" in stripped, "the template's body is code")
        assertEquals(
            stripped.count { it == '{' },
            stripped.count { it == '}' },
            "the template's delimiters must not add a brace of their own",
        )
    }

    @Test
    fun a_url_in_a_string_is_not_a_comment() {
        assertTrue(
            "https://example.com" in KotlinText.stripComments("val url = \"https://example.com\""),
        )
    }

    @Test
    fun a_block_comment_nests() {
        // The outer comment ends at the *second* closing marker.
        val stripped = KotlinText.stripCode("/* a /* b */ c */ val x = 1")
        assertTrue("val x = 1" in stripped, "the whole nested comment is one comment")
    }

    @Test
    fun a_run_of_four_quotes_closes_on_the_last_three() {
        val stripped = KotlinText.stripCode("val a = \"\"\"\"q\"\"\"\" + 1")
        assertTrue("+ 1" in stripped, "the string ended at the last three quotes")
    }

    @Test
    fun an_unterminated_string_costs_an_answer_not_a_wedge() {
        val source = "val e = \"unterminated"
        assertEquals(source.length, KotlinText.stripCode(source).length)
        assertNull(KotlinText.blockBody("{ unclosed"))
    }

    @Test
    fun the_depth_is_counted_per_line() {
        assertTrue(
            KotlinText.lineDepths("class A {\n    val a = 1\n}\n").contentEquals(
                intArrayOf(1, 1, 0),
            ),
        )
    }

    @Test
    fun a_string_brace_does_not_change_the_depth() {
        assertTrue(
            KotlinText.lineDepths("val s = \"}\"\nval t = 1\n").contentEquals(
                intArrayOf(0, 0),
            ),
        )
    }
}
