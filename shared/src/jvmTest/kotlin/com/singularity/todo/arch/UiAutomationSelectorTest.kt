package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * Two static rules about UI-automation selectors, both of which cost a real
 * debugging session on 2026-10-04 and neither of which any existing check
 * could see.
 *
 * They live together because they are one idea: *a selector is a contract, and
 * a contract cannot depend on something the user or the translator can change.*
 * One rule guards the producer side (tags that cannot be found), the other the
 * argument side (tags that change value).
 *
 * Both were found by grep and by reading a captured UI hierarchy, never by a
 * gate. That is the reason they exist now.
 */
@Tag("fast")
class UiAutomationSelectorTest {

    private val commonMainRoot: Path = run {
        val root = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see shared/build.gradle.kts")
        Paths.get(root)
    }

    /**
     * Composables that render into their own Android window. A `testTag` inside
     * one of these does not become a UIAutomator `resource-id`, because the
     * `testTagsAsResourceId` semantics property is set at the app root and is
     * not inherited across windows.
     *
     * `AlertDialog` is only one member of the class — `ModalBottomSheet`,
     * `DropdownMenu` and `Popup` qualify equally, and the first version of this
     * rule that only knew about dialogs left the other three behind.
     */
    private val windowComposables = listOf(
        "AlertDialog(",
        "ModalBottomSheet(",
        "DropdownMenu(",
        "Popup(",
    )

    private val kotlinFiles: List<Path> = Files.walk(commonMainRoot).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    /**
     * A tagged control inside a window-owning surface, where the window does
     * not apply [mapTestTagsAsResourceIds].
     *
     * ## Why the naive version of this rule is wrong
     *
     * A file-level check ("this file opens a window and contains a testTag")
     * has five false positives on the current tree, because most files tag
     * their top bar and open a dialog somewhere else entirely. A gate that
     * cries wolf is worse than no gate, so this one matches braces.
     *
     * Two details that each produced a false positive during development:
     *
     * 1. **Comments are stripped first.** `MenuBottomSheet`'s KDoc names the
     *    composable, and a regex over raw text matched the prose.
     * 2. **The trailing content lambda is included.** For `ModalBottomSheet(
     *    onDismissRequest = … ) { … }` the body lives *after* the call's
     *    closing paren, so matching parens alone reports an empty body and the
     *    rule misses the real case.
     */
    @Test
    fun `a tagged control inside a window-owning surface exposes its testTags`() {
        val violations = mutableListOf<String>()
        var scanned = 0

        kotlinFiles.forEach { file ->
            val code = stripComments(file.readText())
            windowComposables.forEach { window ->
                findOccurrences(code, window).forEach { start ->
                    scanned++
                    val span = code.substring(start, windowBodyEnd(code, start + window.length - 1))
                    if (span.contains("testTag(") && !span.contains("mapTestTagsAsResourceIds")) {
                        val line = code.substring(0, start).count { it == '\n' } + 1
                        violations += "${file.fileName}:$line $window"
                    }
                }
            }
        }

        assertTrue(
            scanned >= 30,
            "scanned only $scanned window-owning call sites — the collector is likely broken " +
                "(expected >= 30 on the current tree)",
        )
        assertTrue(
            violations.isEmpty(),
            "tagged controls inside a window-owning surface with no " +
                "mapTestTagsAsResourceIds (${violations.size}/$scanned):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nA dialog, modal sheet, dropdown or popup renders into its own Android window, " +
                "so the app-root testTagsAsResourceId never reaches it and every testTag inside " +
                "is invisible to Maestro. Apply Modifier.mapTestTagsAsResourceIds() to the " +
                "surface's own modifier. See docs/decisions/2026-10-04-testtag-visibility-helper.md.",
        )
    }

    /**
     * A `testTag` must not be built from a `.label`.
     *
     * `label` is display text by convention in this codebase — it is what a
     * translator edits. `TestTags.taskAction(item.label)` produced
     * `task_action_delete` on an English device and `task_action_удалить` on the
     * project's Russian one, and the flow that wanted `task_action_delete`
     * could never pass. The tag existed, was applied, and was correct; only its
     * *value* moved.
     *
     * `.title` and `.text` are deliberately **not** flagged: on a content model
     * those are the user's data, which a flow seeds and therefore knows. Five
     * of the six `testTag(…title…)` call sites are of that kind and are right.
     * The distinguishing question — is this string chrome or data? — is
     * answerable by the field name for `label` and not for `title`, which is
     * why the rule stops there.
     *
     * The one hit on the current tree (`MenuBottomSheet`, whose menu labels are
     * hardcoded English) is latent: no flow selects `menu_item_*` today. It is
     * reported rather than allowlisted because the same shape was a live bug
     * hours earlier in the same session.
     */
    @Test
    fun `no testTag is built from a display label`() {
        val violations = mutableListOf<String>()
        var scanned = 0

        kotlinFiles.forEach { file ->
            val code = file.readText()
            TEST_TAG_ARG.findAll(code).forEach { match ->
                scanned++
                if (LABEL_ARG.containsMatchIn(match.value)) {
                    val key = "${file.fileName}:${code.substring(0, match.range.first).count { it == '\n' } + 1}"
                    if (key !in LABEL_ALLOWED) {
                        violations += "$key ${match.value.take(70)}"
                    }
                }
            }
        }

        assertTrue(
            scanned >= 80,
            "scanned only $scanned testTag call sites — the collector is likely broken " +
                "(91 on the current tree; the floor is well below that so it only fires on a " +
                "broken regex, not on a shrinking codebase)",
        )
        assertTrue(
            violations.isEmpty(),
            "testTag built from a display label (${violations.size}/$scanned):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nA selector derived from translated text changes with the device locale, " +
                "silently, and no existing check sees it. Pass a stable id instead — see " +
                "TaskEditorMenuItem.action.",
        )
    }

    private companion object {
        /**
         * `MenuBottomSheet.kt:74` — `TestTags.menuItem(item.label)`.
         *
         * Kept as an exception rather than "fixed", and the reason is that the fix
         * was tried and reverted: the labels come from `MenuSections`, hardcoded
         * English in the same file, so they are not a translation surface today.
         * Switching the tag to the destination's class name — the obvious
         * "stable id" — renamed every `menu_item_*` from `menu_profile_sync` to
         * `menu_settings`, and the flows that select the menu by id failed. The
         * label *is* the stable key here, and the localisation risk it carries
         * is theoretical, whereas the breakage of changing it was immediate.
         *
         * If these labels are ever translated, this entry becomes a real bug and
         * the right fix is to give `MenuSection`/`MenuItem` an explicit stable id
         * and update the flows in the same change.
         */
        val LABEL_ALLOWED = setOf("MenuBottomSheet.kt:74", "MenuBottomSheet.kt:80")

        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val TEST_TAG_ARG = Regex("""testTag\([^)\n]*\)""")
        val LABEL_ARG = Regex("""\.\s*(label|actionLabel)\b""")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Replaces block and line comments with blank space, preserving offsets. */
    private fun stripComments(code: String): String {
        var out = code
        // Reversed, so each replacement cannot shift the offsets of the ones
        // still to be done.
        BLOCK_COMMENT.findAll(code).toList().reversed().forEach { m ->
            out = out.replaceRange(m.range, " ".repeat(m.value.length))
        }
        LINE_COMMENT.findAll(out).toList().reversed().forEach { m ->
            out = out.replaceRange(m.range, " ".repeat(m.value.length))
        }
        return out
    }

    private fun findOccurrences(code: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var i = code.indexOf(needle)
        while (i >= 0) {
            out += i
            i = code.indexOf(needle, i + needle.length)
        }
        return out
    }

    /**
     * End offset of the construct that starts at [openIndex] (the `(` or `{`),
     * including a trailing lambda when one follows.
     */
    private fun windowBodyEnd(code: String, openIndex: Int): Int {
        val afterParen = matchPair(code, openIndex, '(', ')')
        var next = afterParen
        while (next < code.length && code[next].isWhitespace()) next++
        if (next < code.length && code[next] == '{') {
            return matchPair(code, next, '{', '}')
        }

        return afterParen
    }

    /** Index just past the pair closing at [openIndex]; string literals skipped. */
    private fun matchPair(code: String, openIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = openIndex
        var inString: Char? = null
        while (i < code.length) {
            val c = code[i]
            val str = inString
            if (str != null) {
                when {
                    c == '\\' -> i++
                    c == str -> inString = null
                }
            } else {
                when (c) {
                    '"', '\'' -> inString = c

                    open -> depth++

                    close -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return code.length
    }
}
