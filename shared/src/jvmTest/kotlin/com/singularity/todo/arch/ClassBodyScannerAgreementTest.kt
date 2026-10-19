package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two implementations of one rule, checked against every real test class.
 *
 * ## The question
 *
 * "Does this class declare a member JUnit will execute?" is answered by
 * [RunnableTestMember], and the answer decides whether a class needs a `@Tag`,
 * and whether `-Ptest.tags` selects it. The rule is: find the end of the class
 * body, and look for a test annotation inside it.
 *
 * Finding the end of the body means counting braces, and a brace count over
 * raw source counts braces that are not braces. A string holding `"{"`, a KDoc
 * mentioning a `{`, a raw string holding a JSON fixture — each shifts the
 * depth, and the body ends in the wrong place.
 *
 * The dangerous direction is specific: a class whose body is mis-scoped is
 * reported as containing no test member, therefore left untagged, therefore
 * never selected by `-Ptest.tags`, therefore never run. That is the whole D1
 * shape — two `@ParameterizedTest` classes reported clean by the gate that
 * exists to report them.
 *
 * ## What was wrong with this test
 *
 * Until 2026-10-19 this test compared a *naive* scanner with a "string-aware"
 * one, and the second was string-aware only about plain double-quoted strings
 * and line comments. It shared the implementation's blind spots for raw
 * strings, for character literals and for block comments — so for the shapes
 * that actually occur, it could not report a disagreement. It was a tripwire
 * with the wire cut.
 *
 * The measurement that followed (ADR
 * `2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates.md`) found **five
 * real test classes** invisible to the inventory for exactly that reason:
 * `ApplyOutcomeArmsTest` (a `"}"` inside a string above the first test),
 * `SharedHarnessConventionsTest` (a `{` in a KDoc), `TaskTimeSlotFailureTest`
 * (a KDoc quoting a code sample that contains a comment of its own),
 * `ArchitectureTest` (a raw string written with a run of four quotes) and
 * `EnqueueSiteIsScannedTest`. All five carry a `@Tag`, so no test was being
 * skipped in CI — but the guard had a hole, and a class that *were* untagged
 * would have been exempt from the requirement and silently excluded.
 *
 * ## What this test is now
 *
 * The scanner is string-aware, and this test compares it against a second,
 * independently written implementation of the same rules — a state machine
 * rather than a set of index jumps — over every test class in the tree. A
 * change that breaks one implementation and not the other is a red test here.
 *
 * The exotic shapes themselves are pinned in
 * `config/test-fixtures/runnable-test-members.txt`, which both languages read,
 * so a shape nobody thought of is still a case both sides must agree on.
 *
 * ## What happens when it fails
 *
 * The failure means the two implementations of the class-body rule disagree.
 * The honest fix is whichever of the two is wrong — and the shape of the
 * disagreement says which: if the oracle is the one that found a test member
 * the scanner did not, the scanner is mis-scoping the body.
 *
 * **Do not** add the offending file to a suppression list. A suppression here
 * records that a gate stopped seeing something, which is the failure mode this
 * repository has already paid for three times.
 */
@Tag("fast")
class ClassBodyScannerAgreementTest {

    private val repoRoot: File = File(
        System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see shared/build.gradle.kts"),
    ).parentFile.parentFile.parentFile.parentFile

    /**
     * The trees whose classes this guard checks, each resolved the way its own
     * module's build declares it.
     *
     * `d2a87600` moved `DesktopTestHarnessEnforcementTest` out of `:shared` on the
     * grounds that a test enforcing another module's conventions belongs in that
     * module, with a relative path. This test is deliberately different, and the
     * difference is worth stating rather than leaving for the next reader to infer.
     *
     * That test enforces **:desktopApp's** conventions. This one checks that **one
     * predicate in :shared** parses every tree identically — and the predicate is
     * the one that decides whether a class in *any* of these modules gets a `@Tag`.
     * Scoped to `:shared` alone it would miss exactly the classes most at risk,
     * because a mis-scoped `desktopApp` class is one that goes untagged and silently
     * never runs.
     *
     * What it must not do is reach across modules with a path nothing declared, or
     * the task goes UP-TO-DATE on an edit and re-reports a stale verdict — the
     * failure `shared/build.gradle.kts` records, measured when a `desktopApp` class
     * was given a tag and the run kept failing because nothing had invalidated the
     * task. So the paths come from declared inputs: `desktopAppJvmTest.root` is
     * declared at `shared/build.gradle.kts`, and both foreign trees are declared
     * as `inputs.dir` on this task.
     */
    private val testSourceDirs: List<File> = listOf(
        File(repoRoot, "shared/src/commonTest"),
        File(repoRoot, "shared/src/jvmTest"),
        // Declared, not derived. See the KDoc above.
        File(System.getProperty("desktopAppJvmTest.root") ?: "desktopApp/src/jvmTest/kotlin"),
        File(repoRoot, "mcp-server/src/test"),
    )

    @Test
    fun the_two_implementations_of_the_class_body_rule_agree() {
        val diverged = mutableListOf<String>()
        var compared = 0

        testSourceDirs.forEach { dir ->
            assertTrue(dir.isDirectory, "missing test source dir: ${dir.path}")
            val root = dir
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith("Test.kt") }
                .forEach { file ->
                    val lines = file.readText().split("\n")
                    val production = RunnableTestMember.codeLines(lines)
                    val oracle = stripLiteralsAndComments(lines.joinToString("\n")).split("\n")
                    for (index in lines.indices) {
                        val name = RunnableTestMember.classHeaderAt(production, index) ?: continue
                        compared++
                        if (RunnableTestMember.classHasTestMember(production, index) !=
                            stringAwareHasTestMember(oracle, index)
                        ) {
                            diverged += "${file.relativeTo(repoRoot).path}:${index + 1} $name"
                        }
                    }
                }
        }

        // A vacuous pass is the failure mode of this test, so the count is asserted
        // too: a scan that covered nothing would agree with anything.
        assertTrue(
            compared > 200,
            "only $compared classes compared — the scan is not covering the tree, so " +
                "agreeing with a string-aware scanner would mean nothing",
        )
        assertEquals(
            diverged,
            emptyList(),
            "the two implementations of the class-body rule now disagree on " +
                "${diverged.size} class(es). A literal or a comment above a test member " +
                "has shifted the span one of them reads:\n" +
                diverged.joinToString("\n") { "  $it" } +
                "\n\nEither make the scanner string-aware, or move the literal below " +
                "the class's last test member. Do not suppress it — see the KDoc.",
        )
    }

    /**
     * The oracle: the same question asked of a body whose comments and literal
     * contents are blanked out first, so only real braces count.
     *
     * It is a second implementation of the same rules, written independently of
     * the one in [RunnableTestMember] — a state machine over the text rather
     * than a set of index jumps. Agreement between them means something: a
     * change that breaks one of them and not the other is a red test here.
     *
     * The two rules that are easy to get wrong, and that this repository
     * actually contains, are:
     *
     *  - **Kotlin block comments nest.** A comment that contains another
     *    comment ends at the second closing marker, not the first. A KDoc here
     *    quotes a code sample that has a comment of its own.
     *  - **A run of four or more quotes** at the end of a raw string means the
     *    first N-3 are content and the last three close it.
     *
     * Neither shape is hypothetical: the first is in `TaskTimeSlotFailureTest`,
     * the second in `ArchitectureTest`. Before they were handled, five real
     * test classes were invisible to the inventory — and this test could not
     * report it, because the oracle shared the implementation's blind spots.
     * That is what the KDoc above this class used to warn about, and it is
     * why the shapes are now written down in
     * `config/test-fixtures/runnable-test-members.txt` as well: the fixture
     * pins them in both languages, and this test checks them against every
     * real class in the tree.
     */
    private fun stringAwareHasTestMember(codeLines: List<String>, classIndex: Int): Boolean {
        if (!codeLines[classIndex].contains('{')) return false
        val body = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in classIndex until codeLines.size) {
            val stripped = codeLines[i]
            body.add(stripped)
            for (ch in stripped) {
                when (ch) {
                    '{' -> {
                        depth++
                        opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth == 0) break
        }
        return body.any { RunnableTestMember.TEST_MEMBER.containsMatchIn(it) }
    }

    /**
     * The file with comments and literal contents blanked, one line per source
     * line.
     *
     * The rules, one per state of [OracleLexer]:
     *
     *  - a line comment runs to the end of the line;
     *  - a block comment runs to its matching closing marker, and block
     *    comments nest;
     *  - a plain string ends at the next unescaped quote, or at the end of the
     *    line if there is none;
     *  - a raw string ends at the last three quotes of a run of three or more;
     *  - a character literal ends at the next unescaped quote.
     */
    private fun stripLiteralsAndComments(text: String): String = OracleLexer(text).strip()

    /**
     * The oracle's lexer: an explicit state machine over the text.
     *
     * Each state says what it consumes and where it goes next, so every rule is
     * stated exactly once and no state can be reached by two different paths.
     * That is the whole point of the oracle: it must be a *different* shape of
     * implementation from [RunnableTestMember]'s index jumps, so that the two
     * can fail apart.
     */
    private class OracleLexer(private val text: String) {

        private enum class State { CODE, LINE_COMMENT, BLOCK_COMMENT, RAW_STRING, STRING, CHARACTER }

        private val out = StringBuilder(text.length)

        /** Nesting depth of the block comment currently being consumed. */
        private var blockDepth = 0

        fun strip(): String {
            var index = 0
            var state = State.CODE
            while (index < text.length) {
                val step = step(state, index)
                index = step.first
                state = step.second
            }
            return out.toString()
        }

        private fun step(state: State, index: Int): Pair<Int, State> = when (state) {
            State.CODE -> codeStep(index)
            State.LINE_COMMENT -> lineCommentStep(index)
            State.BLOCK_COMMENT -> blockCommentStep(index)
            State.RAW_STRING -> rawStringStep(index)
            State.STRING -> stringStep(index)
            State.CHARACTER -> characterStep(index)
        }

        private fun codeStep(index: Int): Pair<Int, State> = when {
            text.startsWith("//", index) -> lineCommentStep(index)

            text.startsWith("/*", index) -> {
                blockDepth = 1
                out.append("  ")
                index + 2 to State.BLOCK_COMMENT
            }

            text.startsWith(TRIPLE, index) -> rawStringStep(index)

            text[index] == QUOTE -> stringStep(index)

            text[index] == SINGLE_QUOTE -> characterStep(index)

            else -> {
                out.append(text[index])
                index + 1 to State.CODE
            }
        }

        private fun lineCommentStep(index: Int): Pair<Int, State> {
            val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
            blank(index, end)
            return end to State.CODE
        }

        private fun blockCommentStep(index: Int): Pair<Int, State> {
            val opens = text.startsWith("/*", index)
            val closes = !opens && text.startsWith("*/", index)
            val step = if (opens || closes) 2 else 1
            if (opens) {
                blockDepth++
            } else if (closes) {
                blockDepth--
            }
            val blanked = if (step == 2) {
                "  "
            } else if (text[index] == '\n') {
                "\n"
            } else {
                " "
            }
            out.append(blanked)
            val next = index + step
            return next to if (blockDepth == 0) State.CODE else State.BLOCK_COMMENT
        }

        private fun rawStringStep(index: Int): Pair<Int, State> {
            val closed = rawStringEnd(index)
            val end = if (closed < 0) text.length else closed
            blank(index, end)
            return end to State.CODE
        }

        /**
         * The index just past the closing marker of the raw string that opens
         * at [start], or -1 when the string is never closed.
         *
         * The closing marker is the last three quotes of the run, so a run of
         * four leaves the first quote as content.
         */
        private fun rawStringEnd(start: Int): Int {
            val first = text.indexOf(TRIPLE, start + 3)
            if (first < 0) return -1
            var run = first
            while (run < text.length && text[run] == QUOTE) run++
            return if (run - first > 3) run else first + 3
        }

        private fun stringStep(index: Int): Pair<Int, State> {
            out.append(QUOTE)
            var i = index + 1
            while (i < text.length) {
                val c = text[i]
                if (c == BACKSLASH) {
                    out.append("  ")
                    i += 2
                } else if (c == QUOTE) {
                    out.append(QUOTE)
                    i++
                    return i to State.CODE
                } else if (text.startsWith(TEMPLATE, i)) {
                    // A template's delimiters are text and its body is code, so
                    // the braces in the body count and the closing `}` does not.
                    out.append("  ")
                    i = templateStep(i + 2)
                } else {
                    out.append(if (c == '\n') '\n' else ' ')
                    i++
                    if (c == '\n') return unterminated(i)
                }
            }
            return unterminated(i)
        }

        /**
         * The body of a string template: code until the `}` that closes it.
         *
         * The template's own delimiters are text — the implementation counts the
         * braces in the body and does not count the `}` that ends the template.
         * An oracle that did not know about templates would report a difference
         * on every file that has one, and a tripwire that cries wolf is a
         * tripwire people learn to switch off.
         */
        private fun templateStep(index: Int): Int {
            var depth = 0
            var i = index
            while (i < text.length) {
                val c = text[i]
                when {
                    text.startsWith("//", i) -> i = lineCommentStep(i).first

                    text.startsWith("/*", i) -> i = blockCommentEnd(i)

                    text.startsWith(TRIPLE, i) -> i = rawStringStep(i).first

                    c == QUOTE -> i = stringStep(i).first

                    c == SINGLE_QUOTE -> i = characterStep(i).first

                    c == '{' -> {
                        depth++
                        out.append(c)
                        i++
                    }

                    c == '}' -> {
                        if (depth == 0) {
                            out.append(' ')
                            return i + 1
                        }
                        depth--
                        out.append(c)
                        i++
                    }

                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return i
        }

        /** The index just past the end of the block comment that starts at [index]. */
        private fun blockCommentEnd(index: Int): Int {
            blockDepth = 0
            var i = index
            while (i < text.length) {
                val (next, state) = blockCommentStep(i)
                i = next
                if (state == State.CODE) return i
            }
            return i
        }

        private fun characterStep(index: Int): Pair<Int, State> {
            out.append(SINGLE_QUOTE)
            var i = index + 1
            while (i < text.length) {
                val c = text[i]
                if (c == BACKSLASH) {
                    out.append("  ")
                    i += 2
                } else if (c == SINGLE_QUOTE) {
                    out.append(SINGLE_QUOTE)
                    i++
                    return i to State.CODE
                } else {
                    out.append(if (c == '\n') '\n' else ' ')
                    i++
                    if (c == '\n') return unterminated(i)
                }
            }
            return unterminated(i)
        }

        /** An unterminated literal swallows the rest of the file. */
        private fun unterminated(from: Int): Pair<Int, State> {
            blank(from, text.length)
            return text.length to State.CODE
        }

        /** Replaces [from until to] with spaces, keeping the line structure. */
        private fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, text.length)) {
                out.append(if (text[k] == '\n') '\n' else ' ')
            }
        }

        private companion object {
            private const val TRIPLE = "\"\"\""
            private const val TEMPLATE = "\${"
            private const val QUOTE = '"'
            private const val SINGLE_QUOTE = '\''
            private const val BACKSLASH = '\\'
        }
    }
}
