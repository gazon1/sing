package com.singularity.todo.feature.search.query

import com.singularity.todo.arch.KotlinText
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grammar and the code agree.
 *
 * ## What is under test
 *
 * `docs/specs/search-query-grammar.md` is the specification of the search query
 * language; `feature/search/` is the code that has to satisfy it. Two descriptions of
 * the same thing drift, and the drift is silent: the grammar gains a rule the code
 * does not implement, or the code implements a rule the grammar never mentions, and
 * the search box answers a question nobody wrote down.
 *
 * This test is the seam between them. It reads the grammar and the code, and it
 * fails when a rule in one has no counterpart in the other.
 *
 * ## What it deliberately does not do
 *
 * It does not contain a copy of the rules. Every rule id and every rule text is read
 * from the grammar itself; the test only compares. A test that carried its own list of
 * the rules would be a second place to forget to update — and the failure it is
 * meant to catch is exactly a rule that was updated in one place and not the other.
 *
 * ## The convention the code follows
 *
 * A rule the code implements is cited next to the implementation, in the grammar's own
 * words: `G2 — conjunction is implicit.` The test looks for that citation, so a rule
 * whose wording changed in the grammar fails the build until the code says the new
 * wording too — and a rule the code implements without saying so fails it as well.
 *
 * ## What is compared
 *
 * - Every rule the grammar declares has a citation in the code that carries the
 *   grammar's own wording.
 * - Every rule the code mentions is one the grammar declares.
 * - Every rule the grammar mentions is one the grammar declares — a dangling
 *   reference in the grammar is as wrong as an unimplemented rule.
 */
@Tag("fast")
class SearchQueryGrammarAgreementTest {

    @Test
    fun `every rule in the grammar is cited by the code in the grammar's own words`() {
        val rules = declaredRules()
        val citations = citationsInCode()

        // A test that compares two empty sets passes, and an empty set here means the
        // grammar and the code were read in the wrong shape. Say so rather than
        // report success.
        assertTrue(rules.isNotEmpty(), "the grammar declares no rules: the rules are read from the rule headings")
        assertTrue(citations.isNotEmpty(), "the code cites no rules: the citations are read from the comments")

        val uncited = rules.filter { (id, headline) ->
            citations.none { it.id == id && it.text.startsWith(headline) }
        }

        assertTrue(
            uncited.isEmpty(),
            "the grammar declares rules the code does not cite in the grammar's own words: " +
                "${uncited.keys.sorted()}\n" +
                "a rule the code implements is cited next to it, and the citation carries the " +
                "grammar's wording before anything else",
        )
    }

    @Test
    fun `every rule the code mentions is declared in the grammar`() {
        val unknown = mentionedIdsInCode() - declaredRules().keys

        assertTrue(
            unknown.isEmpty(),
            "the code mentions rules the grammar does not declare: ${unknown.sorted()}",
        )
    }

    @Test
    fun `every rule the grammar mentions is declared in the grammar`() {
        val dangling = mentionedIdsInGrammar() - declaredRules().keys

        assertTrue(
            dangling.isEmpty(),
            "the grammar mentions rules it does not declare: ${dangling.sorted()}",
        )
    }

    // ── The grammar ───────────────────────────────────────────────────────────

    /** A rule heading — the line a rule is declared on. */
    private val heading = Regex("""\*\*([RGCDF]\d{1,2}) — """)

    /** A whole rule heading, with the text the rule is declared with. */
    private val declaration = Regex("""\*\*([RGCDF]\d{1,2}) — (.+?)\*\*""")

    /** A rule id, wherever it appears. */
    private val ruleId = Regex("""\b([RGCDF]\d{1,2})\b""")

    /** The rules the grammar declares: the id, and the text it declares it with. */
    private fun declaredRules(): Map<String, String> {
        val text = grammar()
        val headings = heading.findAll(text).map { it.groupValues[1] }.toList()
        val declarations = declaration.findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

        assertEquals(
            headings.size,
            declarations.size,
            "every rule heading in the grammar must be on one line: the test reads a rule's " +
                "text from the line that declares it",
        )
        return declarations
    }

    /** Every rule id the grammar mentions, declared or not. */
    private fun mentionedIdsInGrammar(): Set<String> =
        ruleId.findAll(grammar()).map { it.groupValues[1] }.toSet()

    /** The grammar's text. */
    private fun grammar(): String = File(grammarPath()).readText()

    /**
     * Where the grammar is.
     *
     * The path is a system property rather than something derived from the working
     * directory, because the working directory of the test JVM is a build output
     * directory — see the `workingDir` setting in `shared/build.gradle.kts`.
     */
    private fun grammarPath(): String =
        System.getProperty("searchQueryGrammar.spec")
            ?: error(
                "the system property `searchQueryGrammar.spec` is not set: the build file has to " +
                    "say where the grammar is, or this test reads nothing and passes",
            )

    // ── The code ──────────────────────────────────────────────────────────────

    /** A rule the code cites: the id, and the text the citation carries. */
    private data class Citation(val id: String, val text: String)

    /** A citation of a rule — the id, a dash, and the text that follows it. */
    private val citation = Regex("""([RGCDF]\d{1,2}) — (.+)""")

    /** The citations in the comments of the search feature's production sources. */
    private fun citationsInCode(): List<Citation> =
        commentsInCode().flatMap { comments ->
            citation.findAll(comments).map { Citation(it.groupValues[1], it.groupValues[2]) }.toList()
        }

    /** Every rule id the code mentions, in the comments of the production sources. */
    private fun mentionedIdsInCode(): Set<String> =
        commentsInCode()
            .flatMap { comments -> ruleId.findAll(comments).map { it.groupValues[1] }.toList() }
            .toSet()

    /** The comments of the search feature's production sources, file by file. */
    private fun commentsInCode(): List<String> = searchSources().map { commentsOnly(it.readText()) }

    /**
     * The production sources of the search feature and of the core layer that
     * holds its rule.
     *
     * The scope is the feature and the rule, not the whole of `commonMain`:
     * other modules have their own `D1` and `R1` in their comments, and a rule
     * of theirs is not a rule of this grammar.
     *
     * The rule itself moved to `core/search` — beside the plan it decides and
     * the port of the index it is asked through — so the core layer is in the
     * scope too. A citation of a rule belongs next to the rule, and the rule
     * is here now (#885).
     */
    private fun searchSources(): List<File> {
        val commonMain = System.getProperty("commonMain.root")
            ?: error(
                "the system property `commonMain.root` is not set: the build file has to say " +
                    "where the sources are",
            )
        val roots = listOf(
            File(commonMain, "com/singularity/todo/feature/search"),
            File(commonMain, "com/singularity/todo/core/search"),
        )
        roots.forEach { root ->
            assertTrue(root.isDirectory, "the search sources are not where the build file says: $root")
        }
        return roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.sortedBy { it.path }
    }

    /**
     * The file's comments, with everything else blanked out.
     *
     * A rule is cited in a comment, so the code is what has to be taken out before
     * the comments are read — an identifier that happens to spell `G5` is not a
     * citation of the rule. The two blanks come from the one lexer in this
     * repository ([KotlinText]): one has the comments blanked, the other has the
     * comments and the literal contents blanked. A character that is blank in both is
     * in a comment and nowhere else.
     */
    private fun commentsOnly(source: String): String {
        val withoutComments = KotlinText.stripComments(source)
        val codeOnly = KotlinText.stripCode(source)
        val out = CharArray(source.length)
        for (i in source.indices) {
            out[i] = if (withoutComments[i].isWhitespace() && codeOnly[i].isWhitespace()) source[i] else ' '
        }
        return String(out)
    }
}
