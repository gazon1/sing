package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The class-body scanner is not string-aware, and this test is the tripwire for
 * the day that stops being true.
 *
 * ## The weakness
 *
 * [RunnableTestMember.classBody] finds the end of a class by counting braces line
 * by line. A Kotlin string literal holding an unbalanced brace — `"{"`, or a JSON
 * fixture embedded in an assertion message — shifts the depth, so the body ends
 * early or runs long and the answer becomes "is there a test member in the wrong
 * span".
 *
 * The dangerous direction is specific: a class whose body is mis-scoped can be
 * reported as containing no test member, therefore left untagged, therefore never
 * selected by `-Ptest.tags`, therefore never run. That is the whole D1 shape — two
 * `@ParameterizedTest` classes reported clean by the gate that exists to report
 * them.
 *
 * ## Why this is a test and not a lexer
 *
 * Measured on 2026-10-05: **97 lines** in the test tree carry an unbalanced literal
 * brace inside a string, and comparing this scanner against a string-aware one
 * across all 269 real test classes produced **zero** differing verdicts. So the trap
 * is set on 97 lines and nothing has fallen into it — the braces that matter are
 * balanced `${...}` templates, and the unbalanced ones sit after the last test
 * member in their class.
 *
 * Fixing it would mean a real lexer for a defect with zero measured impact, in
 * `commonTest`, which has no parser dependency today. A gate that needs a new build
 * dependency is a gate that gets removed the first time that dependency is
 * inconvenient. So the scanner stays naive and this test watches it instead: it
 * re-measures the same thing on every run, and fails the moment the answer changes.
 *
 * That is the whole design. The issue (#173) said to revisit "when a test file puts
 * a bare `}` in a literal above a test member in its class" — which is exactly the
 * condition this detects, so it no longer depends on anyone noticing.
 *
 * ## What happens when it fails
 *
 * The failure means the scanner is now answering about the wrong span. Two honest
 * fixes, and which one is right depends on why:
 *
 * - the class is a legitimate target, so the scanner needs to become string-aware;
 * - the literal is incidental, so move it out of the class body or below its last
 *   test member.
 *
 * **Do not** add the literal to a suppression list. A suppression here records that
 * a gate stopped seeing something, which is the failure mode this repository has
 * already paid for three times.
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
    fun the_naive_scanner_still_agrees_with_a_string_aware_one() {
        val diverged = mutableListOf<String>()
        var compared = 0

        testSourceDirs.forEach { dir ->
            assertTrue(dir.isDirectory, "missing test source dir: ${dir.path}")
            val root = dir
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith("Test.kt") }
                .forEach { file ->
                    val lines = file.readText().split("\n")
                    for (index in lines.indices) {
                        val name = RunnableTestMember.classHeaderAt(lines, index) ?: continue
                        compared++
                        if (RunnableTestMember.classHasTestMember(lines, index) !=
                            stringAwareHasTestMember(lines, index)
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
            "the naive class-body scanner and a string-aware one now disagree on " +
                "${diverged.size} class(es). A literal brace above a test member has " +
                "shifted the span the scanner reads:\n" +
                diverged.joinToString("\n") { "  $it" } +
                "\n\nEither make the scanner string-aware, or move the literal below " +
                "the class's last test member. Do not suppress it — see the KDoc.",
        )
    }

    /**
     * The oracle: the same question asked of a body whose string literals and line
     * comments are blanked out first, so only real braces count.
     *
     * Written to differ from the implementation in exactly one way — it is
     * string-aware — so that agreement means something. An oracle that shared the
     * implementation's assumptions could not detect the implementation being wrong.
     */
    private fun stringAwareHasTestMember(lines: List<String>, classIndex: Int): Boolean {
        if (!lines[classIndex].contains('{')) return false
        val body = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in classIndex until lines.size) {
            val stripped = stripLiteralsAndComments(lines[i])
            body.add(stripped)
            for (ch in stripped) {
                when (ch) {
                    '{' -> {
                        depth++
                        opened = true
                    }

                    '}' -> depth--

                    else -> Unit
                }
            }
            if (opened && depth == 0) break
        }
        return body.any { RunnableTestMember.TEST_MEMBER.containsMatchIn(it) }
    }

    /**
     * Blank out string literals and line comments, preserving offsets.
     *
     * A character walk rather than a regex, because the oracle has to agree with the
     * production counter on everything *except* string-awareness. A regex would bring
     * its own ideas about what a string is, and an oracle that shares the
     * implementation's blind spots cannot detect it being wrong — which is the only
     * job this method has.
     */
    private fun stripLiteralsAndComments(line: String): String {
        val out = StringBuilder(line.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inString) {
                // Every branch appends exactly one character, the escape ones
                // included. The contract is that the result is the same length as
                // the input, so a caller can map one to the other — the oracle
                // needs that to stay comparable with the production counter.
                when {
                    escaped -> {
                        escaped = false
                        out.append(' ')
                    }

                    ch == BACKSLASH -> {
                        escaped = true
                        out.append(' ')
                    }

                    ch == QUOTE -> {
                        inString = false
                        out.append(' ')
                    }

                    else -> out.append(' ') // keep the offset, drop the content
                }
            } else if (ch == QUOTE) {
                inString = true
                out.append(' ')
            } else if (ch == '/' && i + 1 < line.length && line[i + 1] == '/') {
                // A `//` comment runs to the end of the line by definition.
                repeat(line.length - i) { out.append(' ') }
                break
            } else {
                out.append(ch)
            }
            i++
        }
        return out.toString()
    }

    private companion object {
        private const val BACKSLASH = '\\'
        private const val QUOTE = '"'
    }
}
