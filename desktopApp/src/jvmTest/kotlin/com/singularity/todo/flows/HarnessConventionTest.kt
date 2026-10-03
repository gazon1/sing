package com.singularity.todo.flows

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Enforces the harness conventions the desktop UI test suite runs under.
 *
 * All conventions below are opt-in parameters, and opt-in parameters rot: a new
 * test written without them compiles, passes, and silently skips the check. The
 * whole point of `checkA11y` is that a flow's tree is examined; the whole point
 * of the helpers-only rule is that waits and diagnostics are inherited, not
 * reinvented. Neither survives "whoever remembers".
 *
 * Scanned from source rather than reflection because the flag lives in a default
 * parameter of a lambda, which is invisible at runtime.
 *
 * Scan root is the whole `jvmTest` tree (minus `test/helpers`): the same rule
 * applies to `DesktopAppBootTest`, a harness test that is not a `*FlowTest.kt`.
 * Non-harness composable tests (e.g. `CelebrationTest`, which drives a single
 * composable through `runIsolatedComposeTest`) are exempt by name with a reason.
 */
class HarnessConventionTest {

    private val testSources: List<File> by lazy {
        File("src/jvmTest/kotlin/com/singularity/todo")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.parentFile?.name != "helpers" }
            // This file quotes the selector names in the pattern and KDoc below;
            // scanning itself would always fail.
            .filter { it.name != "HarnessConventionTest.kt" }
            .toList()
    }

    private val flowTests: List<File> by lazy {
        testSources.filter { it.name.endsWith("FlowTest.kt") }
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
        val violations = testSources
            .mapNotNull { file ->
                // Import lines are not call sites; matching them would flag a file
                // for carrying an unused import rather than an actual raw selector.
                val body = file.readLines().filterNot { it.trimStart().startsWith("import ") }
                    .joinToString("\n")
                val calls = RAW_SELECTOR_PATTERN.findAll(body).toList()
                if (calls.isEmpty()) null
                else if (file.name !in EXEMPT_RAW_TAGS) {
                    "${file.name}: ${calls.size} raw call(s)"
                } else null
            }

        if (violations.isEmpty()) return
        fail(
            "Test files with raw selector calls not in EXEMPT_RAW_TAGS:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nMigrate to helpers or add the file to EXEMPT_RAW_TAGS with a reason.",
        )
    }

    @Test
    fun `exempted files actually contain raw selectors`() {
        val stale = EXEMPT_RAW_TAGS.filter { (fileName, _) ->
            val file = testSources.find { it.name == fileName }
            if (file == null) return@filter false
            val body = file.readLines().filterNot { it.trimStart().startsWith("import ") }
                .joinToString("\n")
            RAW_SELECTOR_PATTERN.findAll(body).none()
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
            // Composable-level tests (runIsolatedComposeTest with their own
            // setContent), not runDesktopAppTest harness tests — the helpers-only
            // convention targets app-level flows and does not apply here.
            "CelebrationTest.kt" to "Composable-level test (runIsolatedComposeTest), not the app harness",
            "NotesScreenTest.kt" to "Composable-level test (runIsolatedComposeTest), not the app harness",
            "TagsRenameUiTest.kt" to "Composable-level test (runIsolatedComposeTest), not the app harness",
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
            "expected the project's flow tests under src/jvmTest, found only " +
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
        // Same for the widened raw-selector scan: one file outside feature/flows
        // proves the root really covers the whole tree.
        assertTrue(
            testSources.any { !it.path.contains("feature/flows") },
            "raw-selector scan does not see files outside feature/flows — the scan " +
                "root has regressed to the old narrow root",
        )
    }
}
