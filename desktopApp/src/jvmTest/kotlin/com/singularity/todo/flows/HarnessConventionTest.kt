package com.singularity.todo.flows

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Enforces the harness conventions the desktop flow suite runs under.
 *
 * Both conventions below are opt-in parameters, and opt-in parameters rot: a new
 * flow written without them compiles, passes, and silently skips the check. The
 * whole point of `checkA11y` is that a flow's tree is examined; the whole point
 * of the robot's mandatory `due` is that a fixture's date is a decision. Neither
 * survives "whoever remembers".
 *
 * Scanned from source rather than reflection because the flag lives in a default
 * parameter of a lambda, which is invisible at runtime.
 */
@Tag("fast")
class HarnessConventionTest {

    private val flowTests: List<File> by lazy {
        File("src/jvmTest/kotlin/com/singularity/todo/feature/flows")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("FlowTest.kt") }
            .toList()
    }

    @Test
    fun `every flow test enables the a11y check`() {
        val without = flowTests
            .filter { !it.readText().contains("checkA11y = true") }
            .map { it.name }
            .filterNot { it in EXEMPT_A11Y }
        if (without.isEmpty()) return
        fail(
            "Flow test(s) that do not pass checkA11y = true to runDesktopAppTest:\n" +
                without.joinToString("\n") { "  - $it" } +
                "\nThe check fails on clickable nodes that announce nothing. Opting a " +
                "flow out needs a reason — add the file to EXEMPT_A11Y below with that " +
                "reason, the same convention as knownUnapplied in TestTagsWiringTest.",
        )
    }

    private companion object {
        /**
         * Flow tests permitted to skip the a11y check. Empty today, and meant to
         * stay that way; an entry needs the reason inline, like `knownUnapplied`
         * in TestTagsWiringTest.
         */
        val EXEMPT_A11Y: Set<String> = emptySet()
    }

    @Test
    fun `the scan sees the flow suite`() {
        assertTrue(
            flowTests.size >= 7,
            "expected the project's flow tests under feature/flows, found only " +
                "${flowTests.size}: ${flowTests.map { it.name }}",
        )
        // The guard is vacuous if a convention test can pass while no flow exists,
        // or while the flag has been renamed. One known-green file proves parsing
        // still matches what the harness actually exposes.
        assertTrue(
            flowTests.any { it.readText().contains("checkA11y = true") },
            "no flow test uses checkA11y = true — either the parameter was renamed " +
                "(update this guard) or every flow silently dropped the check",
        )
    }
}
