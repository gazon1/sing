package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.assertFalse
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
     * `MenuBottomSheet` was refactored to use an explicit stable `id` field
     * on each `MenuItem`, so the testTag no longer depends on the label.
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
                    violations += "$key ${match.value.take(70)}"
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

    /**
     * A node carries at most one `testTag`.
     *
     * A Compose node has one test tag in the semantics tree: the merge keeps the
     * outer one, and the second `testTag` on the same chain is silently dropped.
     * The tag is not merely unreachable — it is *applied*, it is correct, and
     * every check that looks for it by name finds the constant in the source.
     *
     * That is how `task_detail_title` and `task_detail_pin_button` died. Both
     * were added as a second `testTag` on a node that already carried an
     * editor tag, four flows asserted them, and no gate — not the selector
     * validator, not the arch test — could see that the flows were asking for
     * a name no tree would ever carry. The flows failed for a reason that had
     * nothing to do with the app.
     *
     * The rule reads the *chain*: two `testTag` calls are on one node when
     * nothing but other modifiers separates them. Two calls in different
     * expressions are not a violation, and the shape that separates them —
     * an assignment, a call, a name — is what this looks for.
     *
     * Known limit: a modifier with a trailing lambda between the two tags
     * (`.testTag(A).semantics { … }.testTag(B)`) is not recognised. The shape
     * that actually happened is recognised, and the positive control below
     * proves the rule still fires.
     */
    @Test
    fun `no node carries two test tags`() {
        val violations = mutableListOf<String>()
        var scanned = 0

        kotlinFiles.forEach { file ->
            val code = stripComments(file.readText())
            val calls = TEST_TAG_CALL.findAll(code).toList()
            scanned += calls.size
            calls.zipWithNext().forEach { (first, second) ->
                val between = code.substring(first.range.last + 1, second.range.first)
                if (between.matches(MODIFIER_CHAIN)) {
                    val line = code.substring(0, first.range.first).count { it == '\n' } + 1
                    violations += "${file.fileName}:$line ${first.value} + ${second.value}"
                }
            }
        }

        assertTrue(
            scanned >= 80,
            "scanned only $scanned testTag call sites — the collector is likely broken " +
                "(the floor is well below the current count so it only fires on a broken " +
                "regex, not on a shrinking codebase)",
        )
        assertTrue(
            violations.isEmpty(),
            "a node carrying two test tags (${violations.size}/$scanned):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nThe second tag is never in the semantics tree: the merge keeps the outer " +
                "one, and a flow that asks for it can never pass. One tag per node — when a " +
                "second name is wanted, it belongs on a second node.",
        )
    }

    /**
     * The positive control.
     *
     * A scan that silently stops matching reports success while checking
     * nothing, which is the specific failure this project has already paid for
     * twice. This feeds the rule the shapes it must catch — and one it must
     * not — and asserts each is recognised the right way round. If the pattern
     * is tightened into uselessness, this fails.
     */
    @Test
    fun `the rule still recognises two tags on one node`() {
        val violations = listOf(
            "Modifier.testTag(A).testTag(B)" to "adjacent calls",
            "Modifier\n    .testTag(A)\n    .testTag(B)" to "calls across lines",
            "Modifier.testTag(A).weight(1f).testTag(B)" to "a modifier between them",
        )
        for ((source, description) in violations) {
            val calls = TEST_TAG_CALL.findAll(source).toList()
            val between = source.substring(calls[0].range.last + 1, calls[1].range.first)
            assertTrue(
                between.matches(MODIFIER_CHAIN),
                "the rule stopped recognising $description — the pattern has been tightened " +
                    "into uselessness",
            )
        }

        val differentNodes = "Modifier.testTag(A)\nText(\"x\")\nModifier.testTag(B)"
        val calls = TEST_TAG_CALL.findAll(differentNodes).toList()
        val between = differentNodes.substring(calls[0].range.last + 1, calls[1].range.first)
        assertFalse(
            between.matches(MODIFIER_CHAIN),
            "the rule reported two tags on different nodes as one node's two tags",
        )
    }

    /**
     * A window that renders **the caller's** content maps the caller's test tags.
     *
     * The rule above is a file-level rule, and a file-level rule has a blind
     * spot that is exactly this project's recurring bug: the content of a sheet
     * is usually written in a *different* file from the one that opens the
     * window. `TaskEditorSheetHost` opens the window; `RecurrencePickerSheet`,
     * `AttachmentsSheet`, `TagsPickerSheet` and five more put the tagged
     * controls inside it. The window's own file contains no `testTag(` at all,
     * so the file-level rule sees nothing and says nothing — while the tagged
     * controls live in a window whose `testTagsAsResourceId` was never
     * asserted.
     *
     * The fix is a deep module: the *host* asserts the mapping, once, on the
     * window that owns the content. Then whether the content is written in the
     * host's file or in another one stops mattering — the property is read
     * from a node's ancestors (Compose walks up the semantics tree of the
     * window), so asserting it at the sheet's root covers the whole subtree.
     *
     * This rule therefore asks a different question of the same windows: does
     * this window render content it did not write itself? If it does, the
     * mapping has to be here — in this file — because the file that wrote the
     * content cannot know which window it will end up in.
     *
     * The positive control below feeds the rule both shapes: a host that
     * renders caller content without the mapping (must be caught) and one that
     * renders its own content without the mapping (must not be caught — the
     * file-level rule above already owns that case, and a rule that cries
     * wolf is worse than no rule).
     */
    @Test
    fun `a window that renders the caller's content maps the test tags`() {
        val violations = mutableListOf<String>()
        var hosts = 0

        kotlinFiles.forEach { file ->
            val code = stripComments(file.readText())
            windowComposables.forEach { window ->
                findOccurrences(code, window).forEach { start ->
                    val open = start + window.length - 1
                    val contentStart = windowContentStart(code, open)
                    if (contentStart < 0) return@forEach
                    val content = code.substring(contentStart, windowBodyEnd(code, open))
                    val parameters = enclosingFunParameters(code, start) ?: return@forEach
                    val rendered = composableParameterNames(parameters)
                    if (rendered.isEmpty()) return@forEach
                    if (LOWERCASE_CALL.findAll(content).none { it.groupValues[1] in rendered }) return@forEach
                    hosts++
                    if (!code.contains("mapTestTagsAsResourceIds")) {
                        val line = code.substring(0, start).count { it == '\n' } + 1
                        violations += "${file.fileName}:$line $window"
                    }
                }
            }
        }

        assertTrue(
            hosts >= 2,
            "found only $hosts window(s) that render the caller's content — the collector is " +
                "likely broken (the floor is well below the current count so it only fires on a " +
                "broken pattern, not on a shrinking codebase)",
        )
        assertTrue(
            violations.isEmpty(),
            "a window renders the caller's content without mapping its test tags " +
                "(${violations.size}/$hosts):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nThe content of this window is written in another file, so that file cannot " +
                "know which window it will land in. Assert Modifier.mapTestTagsAsResourceIds() " +
                "on the window itself — see docs/decisions/2026-10-28-the-sheet-host-maps-the-tags.md.",
        )
    }

    /**
     * The positive control for the host rule.
     *
     * A host that renders caller content is exactly the case the rule exists
     * for; a window that renders only its own content is a case the file-level
     * rule already owns. The rule must tell them apart, and it must keep
     * telling them apart when the pattern is touched.
     */
    @Test
    fun `the host rule tells caller content from own content`() {
        val host = """
            fun SheetHost(
                onDismiss: () -> Unit,
                content: @Composable ColumnScope.() -> Unit,
            ) {
                ModalBottomSheet(onDismissRequest = onDismiss) {
                    content()
                }
            }
        """.trimIndent()
        val ownContent = """
            fun SheetHost(onDismiss: () -> Unit) {
                ModalBottomSheet(onDismissRequest = onDismiss) {
                    Column { Text("hello") }
                }
            }
        """.trimIndent()

        assertTrue(
            rendersCallerContent(host),
            "the rule stopped recognising a host that renders the caller's content — the " +
                "pattern has been tightened into uselessness",
        )
        assertFalse(
            rendersCallerContent(ownContent),
            "the rule reported a window that renders its own content as a host of caller content",
        )
        assertFalse(
            rendersCallerContent(
                """
                    fun SheetHost(onDismiss: () -> Unit, onDelete: () -> Unit) {
                        ModalBottomSheet(onDismissRequest = onDismiss) {
                            onDelete()
                        }
                    }
                """.trimIndent(),
            ),
            "the rule reported a plain callback as caller content — only a composable parameter " +
                "is content",
        )
    }

    private companion object {
        val TEST_TAG_ARG = Regex("""testTag\([^)\n]*\)""")
        val LABEL_ARG = Regex("""\.\s*(label|actionLabel)\b""")

        /**
         * One `testTag(...)` call, with one level of nesting in its argument —
         * `testTag(TestTags.taskItem(title))` is one call, not two.
         */
        val TEST_TAG_CALL = Regex("""testTag\((?:[^()]|\([^()]*\))*\)""")

        /**
         * What may sit between two `testTag` calls that are still on one node:
         * whitespace, dots, and at most one modifier call. A name, an
         * assignment or a second call means the two tags are on different
         * nodes — the chain between them is not a modifier chain any more.
         */
        val MODIFIER_CHAIN = Regex("""^[\s.]*(?:[\w.]+\([^()]*\))?[\s.]*$""")

        /**
         * A call to a lower-case identifier with no arguments — the shape a
         * host uses to render what its caller passed in (`content()`). A
         * composable of this file starts with a capital letter; a callback
         * parameter (`onDelete()`) is not content at all, and the rule tells
         * them apart by the *type* of the parameter, not by its name.
         */
        val LOWERCASE_CALL = Regex("""\b([a-z][A-Za-z0-9_]*)\s*\(\s*\)""")

        /** A parameter name — the shape Kotlin gives to a function parameter. */
        val IDENTIFIER = Regex("""[a-z][A-Za-z0-9_]*""")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Replaces comments with blank space, preserving offsets.
     *
     * [KotlinText] does the work: it tracks string literals, so a `//` inside a URL
     * is not mistaken for a comment and a brace inside a string is not mistaken for
     * code. The regex stripper this replaced could do neither.
     */
    private fun stripComments(code: String): String = KotlinText.stripComments(code)

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

    /**
     * Start offset of the trailing content lambda of the construct that starts
     * at [openIndex] (the `(` of the call), or -1 when the call has none.
     *
     * The content of a window-owning surface lives in the trailing lambda —
     * `ModalBottomSheet(onDismissRequest = …) { … }` — so this is where the
     * surface's own content begins. The argument list is not content: it holds
     * the callbacks the surface is given, not the nodes it shows.
     */
    private fun windowContentStart(code: String, openIndex: Int): Int {
        val afterParen = matchPair(code, openIndex, '(', ')')
        var next = afterParen
        while (next < code.length && code[next].isWhitespace()) next++
        return if (next < code.length && code[next] == '{') next else -1
    }

    /**
     * The parameter list of the function declaration that encloses [pos].
     *
     * "Enclosing" means the nearest `fun` before the position whose parameter
     * list closes before it. A lambda inside the function has no parameters of
     * its own, so the declaration found is the one that owns the window call.
     */
    private fun enclosingFunParameters(code: String, pos: Int): String? {
        var best = -1
        var at = 0
        while (true) {
            val found = code.indexOf("fun ", at)
            if (found < 0 || found > pos) break
            val open = code.indexOf('(', found)
            if (open in 0 until pos) {
                val close = matchPair(code, open, '(', ')')
                if (close <= pos) best = open
            }
            at = found + 4
        }
        if (best < 0) return null
        val close = matchPair(code, best, '(', ')')
        return code.substring(best + 1, close - 1)
    }

    /**
     * The names of the parameters that are *composable* — the ones a host can
     * render as content.
     *
     * The distinction is the type, not the name. `content: @Composable …` is
     * content the caller handed over; `onDelete: () -> Unit` is a callback
     * that happens to be invoked with parentheses, and treating it as content
     * would make the rule fire on every window that calls a callback in its
     * body — which is most of them.
     */
    private fun composableParameterNames(parameterList: String): Set<String> {
        val names = mutableSetOf<String>()
        splitTopLevel(parameterList, ',').forEach { part ->
            val cleaned = part.trim()
            if (cleaned.isEmpty()) return@forEach
            val name = cleaned.substringBefore('=').substringBefore(':').trim()
            if (!IDENTIFIER.matches(name)) return@forEach
            val type = cleaned.substringAfter(':', "").substringBefore('=')
            if (type.contains("@Composable")) names += name
        }
        return names
    }

    /**
     * Whether the source renders caller-provided content inside a window.
     *
     * The positive control drives this directly: a rule that cannot be fed a
     * known shape cannot be shown to still recognise that shape.
     */
    private fun rendersCallerContent(source: String): Boolean {
        val code = stripComments(source)
        windowComposables.forEach { window ->
            findOccurrences(code, window).forEach { start ->
                val open = start + window.length - 1
                val contentStart = windowContentStart(code, open)
                if (contentStart < 0) return@forEach
                val content = code.substring(contentStart, windowBodyEnd(code, open))
                val parameters = enclosingFunParameters(code, start) ?: return@forEach
                val rendered = composableParameterNames(parameters)
                if (rendered.isEmpty()) return@forEach
                if (LOWERCASE_CALL.findAll(content).any { it.groupValues[1] in rendered }) return true
            }
        }
        return false
    }

    /** Splits [text] on [delimiter], ignoring separators inside brackets or strings. */
    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val q = quote
            if (q != null) {
                current.append(c)
                when {
                    c == '\\' && i + 1 < text.length -> {
                        current.append(text[i + 1])
                        i++
                    }

                    c == q -> quote = null
                }
            } else {
                when {
                    c == '"' || c == '\'' -> {
                        quote = c
                        current.append(c)
                    }

                    c == '(' || c == '[' || c == '{' -> {
                        depth++
                        current.append(c)
                    }

                    c == ')' || c == ']' || c == '}' -> {
                        depth--
                        current.append(c)
                    }

                    c == delimiter && depth == 0 -> {
                        parts += current.toString()
                        current.clear()
                    }

                    else -> current.append(c)
                }
            }
            i++
        }
        parts += current.toString()
        return parts
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
