package com.singularity.todo.flows

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
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
 * applies to `DesktopAppBootTest`, a harness test that is not a `*FlowTest.kt`,
 * and to non-harness composable tests (e.g. `CelebrationTest`, which drives a
 * single composable through `runIsolatedComposeTest`) — those receive the same
 * `DesktopComposeUiTest` receiver the harness flows do, so the shared helpers
 * apply to them identically.
 */
@Tag("fast")
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
                // Import lines are not call sites; neither are comment and KDoc
                // lines, which quote the selector names when documenting the
                // rule. This mirrors the filter the display-assert scan below
                // applies, so a KDoc mention of `onNodeWithText(...)` is not
                // mistaken for a call.
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("import ") || t.startsWith("//") ||
                        t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")
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
    fun `raw root lookups are only in test helpers`() {
        val violations = testSources
            .mapNotNull { file ->
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("import ") || t.startsWith("//") ||
                        t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")
                val calls = RAW_ROOT_PATTERN.findAll(body).toList()
                if (calls.isEmpty()) null else "${file.name}: ${calls.size} raw onRoot call(s)"
            }

        if (violations.isEmpty()) return
        fail(
            "Test files with raw onRoot() calls:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\n`onRoot` asserts there is exactly one semantics root, and a Popup " +
                "or a Dialog opens a second one — the call then throws instead of " +
                "telling you what is on screen. Use `semanticsTree()` and " +
                "`semanticsRoots()` from test/helpers, which handle every root.",
        )
    }

    @Test
    fun `exempted files actually contain raw selectors`() {
        val stale = EXEMPT_RAW_TAGS.filter { (fileName, _) ->
            val file = testSources.find { it.name == fileName }
            if (file == null) return@filter false
            // Same filter as the violation scan, so the two agree on what counts
            // as a raw call: imports and comment/KDoc quotes are not call sites.
            val body = file.readLines().filterNot { line ->
                val t = line.trimStart()
                t.startsWith("import ") || t.startsWith("//") ||
                    t.startsWith("*") || t.startsWith("/*")
            }.joinToString("\n")
            RAW_SELECTOR_PATTERN.findAll(body).none()
        }
        if (stale.isEmpty()) return
        fail(
            "EXEMPT_RAW_TAGS entries with no raw selector calls (stale exemptions):\n" +
                stale.entries.joinToString("\n") { "  - ${it.key}: ${it.value}" } +
                "\n\nRemove the stale entry or update it to reflect the actual exemption.",
        )
    }

    @Test
    fun `display assertions go through the helpers outside helper code`() {
        // Presence and display are different waits (#569): a one-shot
        // `assertIsDisplayed` after a presence wait is the race this suite paid
        // for. The helpers (assertTagDisplayed / assertTextDisplayed /
        // assertContentDescriptionDisplayed / assertNodeDisplayed) own the
        // display wait; nothing outside test/helpers may hand-roll it.
        val violations = testSources
            .mapNotNull { file ->
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    // Import lines are not call sites; comment and KDoc lines
                    // quote the pattern when documenting the rule.
                    t.startsWith("import ") || t.startsWith("//") ||
                        t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")
                val calls = DISPLAY_ASSERT_PATTERN.findAll(body).toList()
                if (calls.isEmpty()) null
                else if (file.name !in EXEMPT_DISPLAY_ASSERTS) {
                    "${file.name}: ${calls.size} raw call(s)"
                } else null
            }

        if (violations.isEmpty()) return
        fail(
            "Test files with raw assertIsDisplayed() outside test/helpers:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse assertTagDisplayed / assertTextDisplayed / " +
                "assertContentDescriptionDisplayed / assertNodeDisplayed — they " +
                "wait for layout, which a one-shot assert does not (#569). " +
                "Exempt a file in EXEMPT_DISPLAY_ASSERTS with a reason.",
        )
    }

    @Test
    fun `exempted files actually contain raw display asserts`() {
        val stale = EXEMPT_DISPLAY_ASSERTS.filter { (fileName, _) ->
            val file = testSources.find { it.name == fileName }
            if (file == null) return@filter false
            val body = file.readLines().filterNot { line ->
                val t = line.trimStart()
                t.startsWith("import ") || t.startsWith("//") ||
                    t.startsWith("*") || t.startsWith("/*")
            }.joinToString("\n")
            DISPLAY_ASSERT_PATTERN.findAll(body).none()
        }
        if (stale.isEmpty()) return
        fail(
            "EXEMPT_DISPLAY_ASSERTS entries with no raw display asserts " +
                "(stale exemptions):\n" +
                stale.entries.joinToString("\n") { "  - ${it.key}: ${it.value}" } +
                "\n\nRemove the stale entry.",
        )
    }

    @Test
    fun `drawer and scrollable-container labels use scroll-aware helpers`() {
        // The navigation drawer is a scrolling column whose trailing entries sit below the
        // fold of the 1024x768 test window. A composed-but-off-screen entry can be clicked
        // without error — the click lands on a coordinate outside the window and nothing
        // happens. Use tapTab (which calls performScrollTo) or clickContentDescriptionScrolled.
        val violations = testSources
            .mapNotNull { file ->
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("import ") || t.startsWith("//") ||
                        t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")

                val nonScrollCalls = NON_SCROLL_CD_PATTERN.findAll(body).toList()
                if (nonScrollCalls.isEmpty()) return@mapNotNull null
                if (file.name in EXEMPT_SCROLL) return@mapNotNull null

                val scrollCalls = SCROLL_CD_PATTERN.findAll(body).toList()
                val hasScrollAware = scrollCalls.isNotEmpty()

                // Only flag if there are non-scroll calls but no scroll-aware calls.
                if (nonScrollCalls.isNotEmpty() && !hasScrollAware) {
                    "${file.name}: ${nonScrollCalls.size} non-scroll CD call(s)"
                } else null
            }

        if (violations.isEmpty()) return
        fail(
            "Test files using non-scroll content-description helpers on drawer labels:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse tapTab, clickContentDescriptionScrolled, or " +
                "assertContentDescriptionReachable for DesktopShell.TABS and " +
                "DesktopShell.MENU_ENTRIES entries. Exempt a file in EXEMPT_SCROLL " +
                "with a reason.",
        )
    }

    @Test
    fun `button enabled-state assertions use assertButton helpers`() {
        // assertTextNotEnabled("Rename") matches the Text inside a Material3 TextButton,
        // which carries no Disabled property — enabled = false decorates the button node,
        // not its label. The text-level assertion fails on a correctly disabled button
        // and would pass on a button that was never disabled. assertButtonNotEnabled
        // uses hasClickAction() to select the control node, where Disabled is set.
        val violations = testSources
            .mapNotNull { file ->
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("import ") || t.startsWith("//") ||
                        t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")
                val calls = BUTTON_TEXT_ASSERT_PATTERN.findAll(body).toList()
                if (calls.isEmpty()) null
                else if (file.name !in EXEMPT_BUTTON_ASSERT) {
                    "${file.name}: ${calls.size} button-text assert(s)"
                } else null
            }

        if (violations.isEmpty()) return
        fail(
            "Test files using assertTextEnabled / assertTextNotEnabled on button labels:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse assertButtonEnabled / assertButtonNotEnabled instead — they " +
                "select the control node (hasClickAction()), not the Text label inside it. " +
                "Exempt a file in EXEMPT_BUTTON_ASSERT with a reason.",
        )
    }

    @Test
    fun `exempted files for scroll-reachability actually contain violations`() {
        val stale = EXEMPT_SCROLL.filter { (fileName, _) ->
            val file = testSources.find { it.name == fileName }
            if (file == null) return@filter false
            val body = file.readLines().filterNot { line ->
                val t = line.trimStart()
                t.startsWith("import ") || t.startsWith("//") ||
                    t.startsWith("*") || t.startsWith("/*")
            }.joinToString("\n")
            NON_SCROLL_CD_PATTERN.findAll(body).none()
        }
        if (stale.isEmpty()) return
        fail(
            "EXEMPT_SCROLL entries with no non-scroll CD calls (stale exemptions):\n" +
                stale.entries.joinToString("\n") { "  - ${it.key}: ${it.value}" } +
                "\n\nRemove the stale entry.",
        )
    }

    @Test
    fun `exempted files for button asserts actually contain violations`() {
        val stale = EXEMPT_BUTTON_ASSERT.filter { (fileName, _) ->
            val file = testSources.find { it.name == fileName }
            if (file == null) return@filter false
            val body = file.readLines().filterNot { line ->
                val t = line.trimStart()
                t.startsWith("import ") || t.startsWith("//") ||
                    t.startsWith("*") || t.startsWith("/*")
            }.joinToString("\n")
            BUTTON_TEXT_ASSERT_PATTERN.findAll(body).none()
        }
        if (stale.isEmpty()) return
        fail(
            "EXEMPT_BUTTON_ASSERT entries with no button-text asserts (stale exemptions):\n" +
                stale.entries.joinToString("\n") { "  - ${it.key}: ${it.value}" } +
                "\n\nRemove the stale entry.",
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
         * Files that contain test classes without @Tag, and the reason each exemption exists.
         * Reasons are mandatory — an exemption without a reason is a maintenance trap.
         * Two-way check: validated against actual source in the
         * `exempted files for @Tag actually contain untagged test classes` test.
         */
        val EXEMPT_TAG: Map<String, String> = emptyMap()

        /**
         * Files that contain raw `onNodeWithTag`, `onNodeWithText`,
         * `onNodeWithContentDescription`, or `onAllNodesWithTag` calls outside
         * `test/helpers/`, and the reason each exemption exists.
         *
         * Reasons are mandatory — an exemption without a reason is a maintenance trap.
         * Where possible, migrate the call to a helper and remove the exemption.
         * Two-way check: this map is validated against actual source in the
         * `exempted files actually contain raw selectors` test.
         *
         * Empty today, and meant to stay that way: the four composable-level
         * (`runIsolatedComposeTest`) files that used to sit here were migrated to
         * the shared helpers — `clickTag` / `clickText` for the interaction calls,
         * `assertTagExistsOnUnmergedTree` for the card-render existence checks, and
         * `assertTextEnabled` / `assertTextNotEnabled` for the dialog-button state
         * checks. Those helpers are as applicable to a single-composable test as to
         * a full app-harness flow — the isolation wrapper still hands the test a
         * `DesktopComposeUiTest`, the same receiver the harness flows use.
         */
        val EXEMPT_RAW_TAGS: Map<String, String> = emptyMap()

        /**
         * Files outside `test/helpers/` that contain a raw `.assertIsDisplayed()`
         * call, and the reason each exemption exists. The display-wait helpers
         * (assertTagDisplayed / assertTextDisplayed /
         * assertContentDescriptionDisplayed / assertNodeDisplayed) own the
         * wait-then-assert pattern; a hand-rolled one-shot reintroduces the
         * #569 race. Same two-way check as [EXEMPT_RAW_TAGS].
         */
        val EXEMPT_DISPLAY_ASSERTS: Map<String, String> = mapOf(
            "CreateTaskFlowTest.kt" to
                "Negative control: awaits a nonexistent tag and expects the one-shot " +
                "display assert to fail — the assertion IS the test",
        )

        /**
         * Matches raw selector calls: onNodeWithTag(, onNodeWithText(,
         * onNodeWithContentDescription(, onAllNodesWithTag(.
         * Used with findAll on source to locate violations.
         */
        val RAW_SELECTOR_PATTERN = Regex(
            """onNodeWith(Tag|Text|ContentDescription)\(|onAllNodesWithTag\(""",
        )

        /**
         * Matches a raw root lookup: `onRoot(`.
         *
         * `onRoot` is the one selector that lies about the scene: it asserts
         * there is exactly one root, and a Popup or a Dialog opens a second one.
         * The helpers in test/helpers walk every root instead.
         */
        val RAW_ROOT_PATTERN = Regex("""onRoot\(""")

        /**
         * Matches a raw one-shot display assertion. The helpers' own terminal
         * asserts live in test/helpers, which the scan root already excludes.
         */
        val DISPLAY_ASSERT_PATTERN = Regex("""\.assertIsDisplayed\(\)""")

        /**
         * Files that use non-scroll content-description helpers on DesktopShell drawer labels,
         * and the reason each exemption exists.
         *
         * Non-scroll helpers: `clickContentDescription`, `assertContentDescriptionDisplayed`,
         * `awaitContentDescription`.
         * Scroll-aware alternatives: `tapTab`, `clickContentDescriptionScrolled`,
         * `assertContentDescriptionReachable`.
         *
         * Drawer entries (TABS and MENU_ENTRIES) sit below the fold of the 1024x768 test
         * window. A non-scroll helper finds the composed node and dispatches a click to its
         * coordinates, which are outside the window — a silent no-op.
         *
         * Two-way check: validated against actual source in the
         * `exempted files for scroll-reachability actually contain violations` test.
         */
        val EXEMPT_SCROLL: Map<String, String> = mapOf(
            // assertContentDescriptionDisplayed on DesktopShell.HAMBURGER and DesktopShell.FAB_ADD_TASK
            // — these are shell chrome, not drawer items. The hamburger is always in the top-left
            // corner; the FAB is always bottom-right. Neither scrolls or goes off-screen.
            "DesktopAppBootTest.kt" to "HAMBURGER and FAB_ADD_TASK are shell chrome, not drawer items",
            // FAB is the floating action button, always visible bottom-right — never off-screen.
            "SheetReachabilityTest.kt" to "FAB_ADD_TASK is not a drawer item; always visible",
            // Same: FAB is always visible, not subject to scroll-off-screen in the drawer.
            "TaskRecurrenceScenarioTest.kt" to "FAB_ADD_TASK is not a drawer item; always visible",
            // The Send button belongs to the chat input bar, which is pinned to the bottom of
            // the screen. It is not a drawer item, and it has no scrollable parent at all —
            // the scroll-aware helper would fail on it.
            "ChatInputFieldTest.kt" to "the chat Send button is not a drawer item and has no scrollable parent",
            // Same: HAMBURGER and FAB_ADD_TASK are shell chrome, not drawer items.
            "ShellTestTagReachabilityTest.kt" to "HAMBURGER and FAB_ADD_TASK are shell chrome, not drawer items",
        )

        /**
         * Files that use assertTextEnabled / assertTextNotEnabled on button labels,
         * and the reason each exemption exists.
         *
         * assertTextNotEnabled("Rename") matches the Text inside a Material3 TextButton,
         * which carries no Disabled property. The text-level assertion fails on a correctly
         * disabled button and would pass on a button that was never disabled.
         * assertButtonNotEnabled uses hasClickAction() to select the control node.
         *
         * Two-way check: validated against actual source in the
         * `exempted files for button asserts actually contain violations` test.
         */
        val EXEMPT_BUTTON_ASSERT: Map<String, String> = mapOf(
            // assertTextEnabled on text labels inside search dialog saved-query rows.
            // These are Text nodes inside list-item rows (not Material3 TextButtons), so
            // hasClickAction() does not apply and assertButtonEnabled would fail to find them.
            // The assertions are correct for what they test; the rule targets button labels.
            "SearchPresentationUiTest.kt" to
                "assertTextEnabled on list-item text labels, not Material3 button labels",
        )

        /**
         * Matches non-scroll content-description helpers.
         * These should NOT be used for DesktopShell.TABS or DesktopShell.MENU_ENTRIES entries.
         */
        val NON_SCROLL_CD_PATTERN = Regex(
            """(clickContentDescription|assertContentDescriptionDisplayed|awaitContentDescription)\(""",
        )

        /**
         * Matches scroll-aware content-description helpers.
         * These ARE correct for drawer labels.
         */
        val SCROLL_CD_PATTERN = Regex(
            """(tapTab|clickContentDescriptionScrolled|assertContentDescriptionReachable)\(""",
        )

        /**
         * Matches assertTextEnabled / assertTextNotEnabled — the wrong helpers for button labels.
         * Correct helpers: assertButtonEnabled / assertButtonNotEnabled.
         */
        val BUTTON_TEXT_ASSERT_PATTERN = Regex(
            """assertText(Not)?Enabled\(""",
        )
    }

    @Test
    fun `every test class has a @Tag annotation`() {
        // A test without @Tag is invisible to the `fast` / `slow` filter that gates CI.
        // All test classes must be tagged so that the `-Ptest.tags` selection in CI can
        // include or exclude them deterministically — regardless of whether they are
        // flow tests, composable tests, or any other kind. CI has two selections
        // (`fast` on a pull request, `full` on the nightly) and a class with no
        // tag is outside both of them.
        val classPattern = Regex(
            """^class\s+(\w+)\s*:\s*\w+\s*\{""",
            RegexOption.MULTILINE,
        )

        val violations = testSources
            .mapNotNull { file ->
                val body = file.readText()
                val classes = classPattern.findAll(body).map { it.groupValues[1] }.toList()
                // A test class is one whose name ends in "Test" or "Tests".
                val testClasses = classes.filter { it.endsWith("Test") || it.endsWith("Tests") }
                if (testClasses.isEmpty()) return@mapNotNull null

                val tagPattern = Regex("""@Tag\s*\(\s*"(\w+)"\s*\)""")
                val taggedClasses = mutableSetOf<String>()
                tagPattern.findAll(body).forEach { tagMatch ->
                    val tagValue = tagMatch.groupValues[1]
                    val after = body.substring(tagMatch.range.last + 1)
                    val nextClass = classPattern.find(after)
                    if (nextClass != null) {
                        val name = nextClass.groupValues[1]
                        if (name.endsWith("Test") || name.endsWith("Tests")) {
                            taggedClasses.add(name)
                        }
                    }
                }

                val untagged = testClasses.filter { it !in taggedClasses }
                    .filterNot { it in EXEMPT_TAG }
                if (untagged.isEmpty()) null
                else "${file.name}: untagged test class(es): ${untagged.joinToString()}"
            }

        if (violations.isEmpty()) return
        fail(
            "Test class(es) without @Tag annotation:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nEvery test class must carry @Tag(\"fast\") or @Tag(\"slow\") so that " +
                "the `-Ptest.tags` selection in CI can include or exclude it deterministically. " +
                "Exempt a file in EXEMPT_TAG with a reason.",
        )
    }

    @Test
    fun `exempted files for @Tag actually contain untagged test classes`() {
        // Two-way check: validates that EXEMPT_TAG entries are still valid.
        // An entry whose file no longer has untagged test classes must be removed,
        // otherwise the exemption hides a regression.
        val stale = EXEMPT_TAG.filter { (fileName, _) ->
            val file = testSources.find { it.name == fileName }
            if (file == null) return@filter true // file removed = stale
            val body = file.readText()
            val classes = Regex("""^class\s+(\w+)\s*:\s*\w+\s*\{""", RegexOption.MULTILINE)
                .findAll(body).map { it.groupValues[1] }
                .filter { it.endsWith("Test") || it.endsWith("Tests") }
                .toList()
            if (classes.isEmpty()) return@filter true // no test classes = stale
            // Check if any class is untagged — exemption is stale if none are
            val tagPattern = Regex("""@Tag\s*\(\s*"(\w+)"\s*\)""")
            val taggedClasses = mutableSetOf<String>()
            tagPattern.findAll(body).forEach { tagMatch ->
                val after = body.substring(tagMatch.range.last + 1)
                val nextClass = Regex("""^class\s+(\w+)\s*:\s*\w+\s*\{""", RegexOption.MULTILINE)
                    .find(after)
                if (nextClass != null) {
                    val name = nextClass.groupValues[1]
                    if (name.endsWith("Test") || name.endsWith("Tests")) {
                        taggedClasses.add(name)
                    }
                }
            }
            val untagged = classes.filter { it !in taggedClasses }
            // Keep (mark stale) only if there are no untagged classes — the exemption is no longer needed
            untagged.isEmpty()
        }
        if (stale.isEmpty()) return
        fail(
            "EXEMPT_TAG entries with no untagged test classes (stale exemptions):\n" +
                stale.entries.joinToString("\n") { "  - ${it.key}: ${it.value}" } +
                "\n\nRemove the stale entry.",
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
