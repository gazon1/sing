package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Android shell leaves room below its content, above the navigation suite's bar.
 *
 * ## What went wrong
 *
 * `NavigationSuiteScaffold` measures its content above the navigation bar and consumes the
 * insets that bar occupies, so a bottom inset read from inside the shell resolves to zero.
 * Its content lambda takes no `PaddingValues` either — not in the version this project
 * resolves — so there was no padding to forward. The shell therefore gave its screens no
 * bottom space at all: every screen's last row sat flush against the bar, and a screen
 * whose own layout is taller than its area was clipped by it (#1699).
 *
 * ## What this holds
 *
 * That padding is the only thing standing between the screens and the bar, and it is one
 * line in one file: nothing about it fails when it is deleted. This test reads the shell's
 * source — as the other source-scanning architecture tests read theirs — and fails when the
 * padding is gone.
 *
 * The assertion is spelled against the constant the shell defines, so a bottom padding
 * somewhere else in the file cannot satisfy it, and the source root comes from the
 * `androidMain.root` property the `archTest` task declares as an input — a derived path
 * would leave the test reading a tree Gradle does not know it depends on, and report a
 * stale pass. The first test pins that the file and the function are where this test
 * thinks they are, so the second cannot pass over an empty body.
 */
@Tag("fast")
class AndroidShellBottomInsetTest {
    private companion object {
        private const val SHELL_FILE = "AndroidShellNav3.kt"
        private const val SHELL_ROOT = "fun androidShellNav3Root("
        private const val BOTTOM_PADDING = "SHELL_CONTENT_BOTTOM_PADDING"

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
