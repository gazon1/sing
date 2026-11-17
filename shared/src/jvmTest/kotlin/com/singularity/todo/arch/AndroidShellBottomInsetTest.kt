package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android shell keeps a bottom window inset for its content, above the navigation bar.
 *
 * ## What went wrong
 *
 * The inner `Scaffold` stripped the bottom side of the window insets down to the horizontal
 * sides alone. Every screen's last row was therefore drawn behind the navigation suite's bar
 * (#1699).
 *
 * ## What the fix is, and what this holds
 *
 * The fix is the `contentWindowInsets` argument the shell hands that scaffold — a bottom
 * side must be present in it. It cannot come from reading `WindowInsetsSides.Bottom` on the
 * scaffold's own insets: `NavigationSuiteScaffold` wraps its content in
 * `navigationSuiteScaffoldConsumeWindowInsets`, which consumes
 * `NavigationBarDefaults.windowInsets.only(Bottom)`, so the bottom of
 * `ScaffoldDefaults.contentWindowInsets` read inside the suite is zero and that side alone
 * changes nothing. The shell reads the inset above the suite and passes it as a length
 * (`WindowInsets(bottom = ...)`).
 *
 * The check reads the shell's source, as the other source-scanning architecture tests read
 * theirs, and looks only at the argument that decides the property: the text of
 * `contentWindowInsets = ...`, with whitespace removed, so a reformat does not turn it red.
 * It is deliberately not spelled against one form of the fix — a `WindowInsetsSides.Bottom`
 * side and a `WindowInsets(bottom = ...)` length both satisfy it — so re-expressing the fix
 * without removing it does not turn this red either.
 *
 * A one-sided predicate guards nothing on its own: `contains("WindowInsets(bottom")` is
 * also true of the empty string, and a predicate that is merely *absent* passes every file.
 * The third test is therefore a positive control — it runs the same extraction and the same
 * bottom predicate over the shape that shipped the bug (`.only(WindowInsetsSides.Horizontal)`,
 * nothing put back) and requires it to be rejected. If that test ever goes green with the
 * real shell's test, the pair has stopped discriminating and one of them is wrong.
 *
 * The top side is out of scope: `ShellTopBar` pads for the status bar and the scaffold must
 * keep leaving the top out (#1566). The fourth test holds that half, so a future "take the
 * whole inset" does not silently reintroduce the double top padding with no red gate.
 *
 * The source root comes from the `androidMain.root` property the `archTest` task declares as
 * an input — a derived path would leave the test reading a tree Gradle does not know it
 * depends on, and report a stale pass. The first test pins that the file, the function and
 * the argument are where this test thinks they are, so the others cannot pass over an empty
 * body.
 */
@Tag("fast")
class AndroidShellBottomInsetTest {
    private companion object {
        private const val SHELL_FILE = "AndroidShellNav3.kt"
        private const val SHELL_ROOT = "fun androidShellNav3Root("
        private const val INSET_ARGUMENT = "contentWindowInsets"

        /**
         * The shell as it shipped the bug: the bottom side stripped from the scaffold's
         * window insets and nothing else put back (#1699). The bottom predicate below must
         * reject this shape, or it is not guarding anything.
         */
        private const val PRE_FIX_INSETS: String =
            "contentWindowInsets = ScaffoldDefaults.contentWindowInsets" +
                ".only(WindowInsetsSides.Horizontal)"

        /** `only(...)` asking for the bottom side, alone or alongside others. */
        private val BOTTOM_SIDE = Regex("""only\([^)]*WindowInsetsSides\.Bottom""")

        /** The Android platform sources, handed over by the `archTest` task. */
        private val shellSource: String by lazy {
            val root =
                File(
                    System.getProperty("androidMain.root")
                        ?: error(
                            "androidMain.root system property is not set — " +
                                "see the archTest task config in shared/build.gradle.kts",
                        ),
                )
            File(root, "com/singularity/todo/$SHELL_FILE").readText()
        }
    }

    /** The shell root's body: its signature up to the function's own closing brace. */
    private fun shellRootBody(): String {
        val lines = shellSource.lines()
        val start = lines.indexOfFirst { it.startsWith(SHELL_ROOT) }
        if (start < 0) return ""
        val end = lines.withIndex().drop(start + 1).firstOrNull { (_, line) -> line == "}" }?.index
        return if (end == null) "" else lines.subList(start, end).joinToString("\n")
    }

    /**
     * The `contentWindowInsets = ...` argument of the scaffold, with all whitespace removed.
     *
     * The argument is a call chain rather than a literal, so it is read by walking forward
     * from the `=` and stopping at the first top-level `,` or at the parenthesis that closes
     * the scaffold call — not by matching a fixed spelling of the fix.
     *
     * @return `null` when the source has no such argument at all.
     */
    private fun contentWindowInsetsArgument(source: String): String? {
        val marker = "$INSET_ARGUMENT ="
        val markerStart = source.indexOf(marker)
        if (markerStart < 0) return null
        val start = markerStart + marker.length
        var depth = 0
        var index = start
        while (index < source.length) {
            val char = source[index]
            if (char == '(') {
                depth++
            } else if (char == ')') {
                if (depth == 0) break
                depth--
            } else if (char == ',' && depth == 0) {
                break
            }
            index++
        }
        return source.substring(start, index).filterNot { it.isWhitespace() }
    }

    /**
     * True when the argument puts something back on the bottom edge: a `WindowInsets(bottom = …)`
     * length, or an `only(...)` that includes `WindowInsetsSides.Bottom`.
     */
    private fun keepsBottomInset(argument: String): Boolean =
        argument.contains("WindowInsets(bottom=") || BOTTOM_SIDE.containsMatchIn(argument)

    /**
     * True when the argument leaves the top edge to `ShellTopBar` (#1566): it takes the
     * horizontal sides or the bottom side, and nothing that carries the top.
     */
    private fun leavesTopInsetOut(argument: String): Boolean =
        !argument.contains("WindowInsetsSides.Top") &&
            !argument.contains("WindowInsets(top=") &&
            (
                argument.contains("only(WindowInsetsSides.Horizontal") ||
                    argument.contains("only(WindowInsetsSides.Bottom")
            )

    @Test
    fun `the shell source is where this test thinks it is`() {
        assertTrue(shellSource.contains(SHELL_ROOT), "$SHELL_FILE no longer declares $SHELL_ROOT")
        val body = shellRootBody()
        assertTrue(body.isNotBlank(), "could not read the body of $SHELL_ROOT")
        assertNotNull(
            contentWindowInsetsArgument(body),
            "$SHELL_ROOT no longer passes $INSET_ARGUMENT to its Scaffold, so the bottom " +
                "inset has nowhere to be decided (#1699).",
        )
    }

    @Test
    fun `the shell keeps a bottom window inset for its content`() {
        val argument = contentWindowInsetsArgument(shellRootBody())
        assertNotNull(argument, "no $INSET_ARGUMENT argument in $SHELL_ROOT")
        assertTrue(
            keepsBottomInset(argument),
            "The Android shell no longer keeps a bottom inset for its content. " +
                "NavigationSuiteScaffold consumes the navigation bar's own window inset, so " +
                "a bottom inset read inside the shell is zero: without this the last row of " +
                "every screen sits under the bar (#1699). Argument was: $argument",
        )
    }

    /** Positive control: the same predicate must reject the shape that shipped the bug. */
    @Test
    fun `the bottom check rejects the shell that shipped the bug`() {
        val preFix = "$SHELL_ROOT…)\n$PRE_FIX_INSETS,\n}\n"
        val argument = contentWindowInsetsArgument(preFix)
        assertNotNull(argument, "the pre-fix fixture is not readable by this test's own parser")
        assertFalse(
            keepsBottomInset(argument),
            "The bottom check accepts the pre-fix shell ($PRE_FIX_INSETS), so it cannot fail " +
                "for the regression it exists to catch (#1699, #2192).",
        )
    }

    /** The other half of #2192: this check must not start demanding the top inset (#1566). */
    @Test
    fun `the shell still leaves the top inset to the top bar`() {
        val argument = contentWindowInsetsArgument(shellRootBody())
        assertNotNull(argument, "no $INSET_ARGUMENT argument in $SHELL_ROOT")
        assertTrue(
            leavesTopInsetOut(argument),
            "The Android shell now takes the top window inset in $INSET_ARGUMENT. " +
                "ShellTopBar already pads for the status bar, so this applies it twice " +
                "(#1566). Argument was: $argument",
        )
    }

    /**
     * The pre-fix shape must fail the top predicate too, or the fourth test would pass over
     * a fix that only ever added sides and never chose between them.
     */
    @Test
    fun `the top check reads the argument rather than defaulting to true`() {
        assertFalse(
            leavesTopInsetOut("ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Vertical)"),
            "the top check accepts an inset that carries the top side",
        )
        assertNull(
            contentWindowInsetsArgument("fun androidShellNav3Root(…) {\n    Scaffold() { }\n}"),
            "a shell without the argument must read as absent, not as a passing empty string",
        )
    }
}
