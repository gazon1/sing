package com.singularity.todo.flows

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Enforces the harness conventions the desktop flow suite runs under.
 *
 * All three conventions are opt-in parameters, and opt-in parameters rot: a new
 * flow written without them compiles, passes, and silently skips the check. The
 * whole point of `checkA11y` is that a flow's tree is examined; the whole point
 * of the robot's mandatory `due` is that a fixture's date is a decision. Neither
 * survives "whoever remembers".
 *
 * Scanned from source rather than reflection because the flag lives in a default
 * parameter of a lambda, which is invisible at runtime.
 */
class HarnessConventionTest {

    private val flowTests: List<File> by lazy {
        File("src/jvmTest/kotlin/com/singularity/todo")
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

    @Test
    fun `raw selectors are only in test helpers or explicitly exempted`() {
        val violations = flowTests
            .filter { it.parentFile?.name != "helpers" }
            .mapNotNull { file ->
                val calls = RAW_SELECTOR_PATTERN.findAll(file.readText()).toList()
                if (calls.isEmpty()) null
                else if (file.name !in EXEMPT_RAW_TAGS) {
                    "${file.name}: ${calls.size} raw call(s)"
                } else null
            }

        if (violations.isEmpty()) return
        fail(
            "Flow test files with raw selector calls not in EXEMPT_RAW_TAGS:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nMigrate to helpers or add the file to EXEMPT_RAW_TAGS with a reason.",
        )
    }

    @Test
    fun `exempted files actually contain raw selectors`() {
        val stale = EXEMPT_RAW_TAGS.filter { (fileName, _) ->
            val file = flowTests.find { it.name == fileName }
            // Only check files that exist in flowTests; files outside the scan root
            // (e.g. DesktopAppBootTest.kt) are not validated here.
            file != null && RAW_SELECTOR_PATTERN.findAll(file.readText()).none()
        }
        if (stale.isEmpty()) return
        fail(
            "EXEMPT_RAW_TAGS entries with no raw selector calls (stale exemptions):\n" +
                stale.entries.joinToString("\n") { "  - ${it.key}: ${it.value}" } +
                "\n\nRemove the stale entry or update it to reflect the actual exemption.",
        )
    }

    private companion object {
        /**
         * Flow tests permitted to skip the a11y check. Empty today, and meant to
         * stay that way; an entry needs the reason inline, like `knownUnapplied`
         * in TestTagsWiringTest.
         */
        val EXEMPT_A11Y: Set<String> = emptySet()

        /**
         * Files that contain raw `onNodeWithTag`, `onNodeWithText`,
         * `onNodeWithContentDescription`, or `onAllNodesWithTag` calls outside
         * `test/helpers/`, and the reason each exemption exists.
         *
         * Reasons are mandatory — an exemption without a reason is a maintenance trap.
         * Where possible, migrate the call to a helper and remove the exemption.
         * Two-way check: this map is validated against actual source in the
         * `exempted files actually contain raw selectors` test.
         */
        val EXEMPT_RAW_TAGS: Map<String, String> = mapOf(
            // DesktopAppBootTest: Boot screen has no task tags — tests production App
            // startup without seeded data, so task-based helpers are inapplicable.
            // (Not a FlowTest; listed for when the guard's scan root widens.)
            "DesktopAppBootTest.kt" to "Boot screen has no task tags; tests production App startup without seeded data",
        )

        /**
         * Matches raw selector calls: onNodeWithTag(, onNodeWithText(,
         * onNodeWithContentDescription(, onAllNodesWithTag(.
         * Used with findAll on source to locate violations.
         */
        val RAW_SELECTOR_PATTERN = Regex(
            """onNodeWith(Tag|Text|ContentDescription)\(|onAllNodesWithTag\(""",
        )
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
