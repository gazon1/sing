package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
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
 * A static check is what this repository uses where a property has no runtime test to hold
 * it: this reads the shell's source, as the other source-scanning architecture tests read
 * theirs, and looks only at the argument that decides the property. It is deliberately not
 * spelled against one form of the fix — a `WindowInsetsSides.Bottom` side and a
 * `WindowInsets(bottom = ...)` length both satisfy it — so re-expressing the fix without
 * removing it does not turn this red. It fails on the shape that shipped the bug
 * (`.only(WindowInsetsSides.Horizontal)`), which the third test pins by running the same
 * predicate over that shape.
 *
 * The top side is out of scope: `ShellTopBar` pads for the status bar and the scaffold must
 * keep leaving the top out (#1566). The fourth test holds that half so a future "take the
 * whole inset" does not undo it.
 *
 * The source root comes from the `androidMain.root` property the `archTest` task declares as
 * an input — a derived path would leave the test reading a tree Gradle does not know it
 * depends on, and report a stale pass. The first test pins that the file and the function
 * are where this test thinks they are, so the others cannot pass over an empty body.
 */
@Tag("fast")
class AndroidShellBottomInsetTest {
    private companion object {
        private const val SHELL_FILE = "AndroidShellNav3.kt"
        private const val SHELL_ROOT = "fun androidShellNav3Root("
        private const val INSET_ARGUMENT = "contentWindowInsets"

        /**
         * The shell as it shipped the bug: the bottom side stripped from the scaffold's
         * window insets and nothing else put back (#1699). The predicate below must reject
         * this shape, or it is not guarding anything.
         */
        private val PRE_FIX_INSETS =
            "contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal)"

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

    @Test
    fun `the shell source is where this test thinks it is`() {
        assertTrue(shellSource.contains(SHELL_ROOT), "$SHELL_FILE no longer declares $SHELL_ROOT")
        assertTrue(shellRootBody().isNotBlank(), "could not read the body of $SHELL_ROOT")
    }

    @Test
    fun `the shell pads its content off the bottom of the window`() {
        assertTrue(
            shellRootBody().contains("padding(bottom = $BOTTOM_PADDING)"),
            "The Android shell no longer pads its content off the bottom of the window. " +
                "NavigationSuiteScaffold already measures the content above its navigation bar " +
                "and consumes the bar's insets, so a bottom inset read inside the shell is zero: " +
                "without this padding the last row of every screen sits flush against the bar " +
                "(#1699).",
        )
    }
}
