package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin half of the shared "where does this block end" fixture table.
 *
 * `config/test-fixtures/kotlin-brace-matching.txt` is the single definition of
 * the answer. This suite proves [KotlinText] agrees with it;
 * `scripts/tests/test_kotlin_brace_matching.py` proves the same for the Python
 * lexer.
 *
 * ## Why a data file and not shared constants
 *
 * The two implementations are in two languages, so a test written in one of
 * them cannot compare them. The fixtures are the contract, and both suites
 * read the same bytes.
 *
 * ## What the table measures
 *
 * Where a block ends, which is the question every rule that reads a function
 * body asks. A brace counter over raw source answers it wrong whenever a brace
 * is not a brace — because it lives in a string, in a KDoc or in the text of a
 * string template.
 *
 * The measured damage of getting it wrong: **103 function bodies** in 27 files
 * of `:shared` end in the wrong place, and five real test classes were
 * invisible to the test-class inventory. The `unclosed` record is the positive
 * control — a matcher that answered "the end of the file" for everything would
 * pass every other record.
 */
@Tag("fast")
class KotlinTextFixtureTest {

    private val repoRoot: File = File(
        System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see shared/build.gradle.kts"),
    ).parentFile.parentFile.parentFile.parentFile

    private val fixtureFile = File(repoRoot, "config/test-fixtures/kotlin-brace-matching.txt")

    /** A record of the shared table: its id, the agreed body, and its source. */
    private data class Fixture(val id: String, val expected: String?, val source: String)

    @Test
    fun every_shared_fixture_matches_its_agreed_body() {
        val failures = loadFixtures()
            .filter { KotlinText.blockBody(it.source) != it.expected }
            .joinToString("\n") { "  ${it.id}: expected ${it.expected}, got ${KotlinText.blockBody(it.source)}" }

        assertTrue(
            failures.isEmpty(),
            "the Kotlin block matcher disagrees with the shared fixture table at " +
                "${fixtureFile.path}:\n$failures\n\n" +
                "If the two lexers disagree about where a block ends, one of them is " +
                "wrong, and the fixture table says which — the shape of the disagreement " +
                "names the rule that was broken.",
        )
    }

    @Test
    fun the_table_covers_the_hard_shapes() {
        val ids = loadFixtures().map { it.id }.toSet()
        listOf(
            "string_with_a_closing_brace",
            "raw_string_with_a_brace",
            "kdoc_with_a_brace",
            "nested_block_comment",
            "char_literal_brace",
            "url_in_a_string",
            "template_with_a_block",
        ).forEach { required ->
            assertTrue(required in ids, "the table has no $required record")
        }
    }

    @Test
    fun the_table_has_an_unclosed_record() {
        assertTrue(
            loadFixtures().any { it.expected == null },
            "the table has no unclosed record, so a matcher that answered \"the end of the " +
                "file\" for everything would pass",
        )
    }

    @Test
    fun fixture_ids_are_unique() {
        val ids = loadFixtures().map { it.id }
        assertEquals(ids.distinct().size, ids.size, "duplicate fixture ids")
    }

    /**
     * A record header is `=== <id> | <expected body>`; the source is every
     * following line up to the next header.
     *
     * A malformed record raises rather than being skipped: a table that silently
     * loses a record is how a green suite starts asserting nothing.
     */
    private fun loadFixtures(): List<Fixture> {
        assertTrue(
            fixtureFile.isFile,
            "missing shared fixture table: ${fixtureFile.path} — it is the contract " +
                "between the Kotlin and the Python lexer, and both suites read it",
        )
        val records = mutableListOf<Fixture>()
        var id: String? = null
        var expected: String? = null
        var decoded = false
        val body = mutableListOf<String>()

        fun flush() {
            val currentId = id
            val currentExpected = expected
            if (currentId != null && decoded) {
                records += Fixture(currentId, currentExpected, body.joinToString("\n"))
            }
        }

        fixtureFile.readLines().forEach { line ->
            if (line.startsWith(HEADER)) {
                flush()
                val header = line.removePrefix(HEADER)
                val separator = header.indexOf('|')
                check(separator > 0) { "fixture header without a separator: $line" }
                id = header.substring(0, separator).trim()
                val raw = header.substring(separator + 1).trim()
                expected = if (raw == UNCLOSED) null else decode(raw)
                decoded = raw == UNCLOSED || raw.isNotEmpty()
                body.clear()
            } else if (id != null) {
                // Every other line inside a record is Kotlin source, comments and
                // blank lines included — the two readers must hand the matchers
                // byte-identical input or their agreement means nothing. Lines
                // above the first record are the table's own header comment.
                body.add(line)
            }
        }
        flush()

        assertTrue(records.isNotEmpty(), "no fixtures parsed from ${fixtureFile.path}")
        return records
    }

    /** The expected body as text: `\n` is a newline, `\t` a tab, `\\` a backslash. */
    private fun decode(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(next)
                }
                i += 2
                continue
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private companion object {
        private const val HEADER = "=== "
        private const val UNCLOSED = "unclosed"
    }
}
