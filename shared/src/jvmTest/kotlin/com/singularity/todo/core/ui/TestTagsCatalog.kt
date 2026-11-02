package com.singularity.todo.core.ui

import com.singularity.todo.arch.KotlinText
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Regex

/**
 * Parse-only view of [TestTags] — extracts constant and function declarations
 * from the source file without executing any code.
 *
 * Used by the wiring tests to verify declared tags are reachable from code,
 * and by the golden comparison to verify TAGS.md is up-to-date.
 *
 * ## Why parse the source file instead of using reflection?
 *
 * - Dynamic functions like `fun taskItem(title: String) = "task_item_${slug(title)}"`
 *   require passing arguments to `fn.call()`; the prefix can only be read from the
 *   raw string template without evaluating the slug.
 * - Source parsing is deterministic and works in `commonMain` without platform dependencies.
 * - `TAGS.md` regeneration is a text operation, not a reflection operation.
 *
 * ## Source location
 *
 * Parses the file at [TEST_TAGS_SOURCE] relative to the `:shared` module root,
 * resolved from the `commonMain.root` system property set in
 * `shared/build.gradle.kts`.
 */
object TestTagsCatalog {
    /**
     * Path to [TestTags.kt] source, relative to the `:shared` module root.
     * `commonMain.root` points to `src/commonMain/kotlin`, so three parent levels up
     * is the module root.
     */
    const val TEST_TAGS_SOURCE = "src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt"

    private val CONST_REGEX = Regex("""\bconst\s+val\s+(\w+)\s*(?::[^=]+)?=\s*"([^"]+)"""")
    private val DYNAMIC_FUN_REGEX = Regex("""\bfun\s+(\w+)\s*\([^)]*\)\s*(?::[^=]+)?=\s*"([^"]*)\$\{slug\(""")
    private val OBJECT_REGEX = Regex("""^\s*(?:\w+\s+)*object\s+(\w+)\s*\{""")

    /**
     * Every static constant, including the ones declared inside a nested `object`
     * (e.g. `Dialog.CONFIRM`, `Pomodoro.PHASE_LABEL`).
     *
     * Nested constants get a qualified name so that same-named constants in
     * different nested objects never collide. Top-level constants keep their bare
     * name (`AUTH_EMAIL_INPUT`) because that is how call sites read them.
     */
    fun staticTags(): List<Pair<String, String>> = scanDeclarations(CONST_REGEX) { name, value -> name to value }

    /**
     * Every dynamic tag function of the form `fun foo(...) = "prefix_${slug(...)}"`,
     * with the prefix captured as everything before `${slug(`. Functions declared in
     * a nested object are qualified the same way constants are (`Dialog.title`).
     *
     * A function whose body does not route through `slug()` (e.g. `calendarDay`,
     * which formats an ISO date itself) is not a slug-expanding tag and is skipped.
     */
    fun dynamicFunctions(): List<Pair<String, String>> =
        scanDeclarations(DYNAMIC_FUN_REGEX) { name, value -> name to value }

    /**
     * Single pass over the source that resolves each declaration's enclosing
     * `object` path, so nested declarations are reported qualified.
     *
     * Brace depth is tracked explicitly rather than inferred from indentation:
     * `TestTags.kt` mixes one-line `object X {` headers with a top-level function
     * whose body opens on the same line as its signature.
     *
     * [declRegex] must have the declared name in group 1 and the tag value in group 2.
     * [build] receives the owner-qualified name and that value.
     */
    private fun scanDeclarations(
        declRegex: Regex,
        build: (qualifiedName: String, value: String) -> Pair<String, String>,
    ): List<Pair<String, String>> {
        val source = readTestTagsSource()
        val result = mutableListOf<Pair<String, String>>()

        // Stack of (brace depth at which the object opened, object name).
        val objectStack = ArrayDeque<Pair<Int, String>>()
        // Depth of the `object TestTags` block itself; nothing outside it is a tag.
        var rootDepth: Int? = null
        // The line `object TestTags` opens on; a declaration before it is not a tag.
        var rootLine = -1

        // The comments are blanked and the literals are kept, because a declaration
        // is recognised by the *value* it holds — `= "prefix_${slug(name)}"` — and
        // that value lives inside a string literal.
        //
        // The depths come from the same walk, so a brace inside a string or inside a
        // KDoc does not move the depth of the lines after it. Counting braces per
        // line over raw text did, and it is what mis-attributed a nested
        // declaration to the wrong object.
        val lines = KotlinText.stripComments(source).lines()
        val depths = KotlinText.lineDepths(source)

        // The owner of every line, resolved before any declaration is read: the
        // declaration a line belongs to may start on an earlier line, so the owner
        // has to be known first.
        val ownersByLine = HashMap<Int, List<String>>()

        for ((index, code) in lines.withIndex()) {
            // The depth at the *start* of the line: an object header opens its block
            // on its own line, so the block's depth is the depth before it.
            val depth = if (index == 0) 0 else depths.getOrElse(index - 1) { 0 }

            OBJECT_REGEX.find(code)?.let { found ->
                if (rootDepth == null && found.groupValues[1] == "TestTags") {
                    rootDepth = depth
                    rootLine = index
                }
                objectStack.addLast(depth to found.groupValues[1])
            }

            // Enclosing objects = everything on the stack opened below `object TestTags`.
            val root = rootDepth
            ownersByLine[index] =
                if (root == null) {
                    emptyList()
                } else {
                    objectStack.filter { it.first > root }.map { it.second }
                }

            // An object opened at depth d is closed once the depth falls back to d.
            val endDepth = depths.getOrElse(index) { depth }
            while (objectStack.isNotEmpty() && objectStack.last().first >= endDepth) {
                objectStack.removeLast()
            }
        }

        // The declarations are read from *chunks* of lines, not from single lines.
        //
        // A declaration may be wrapped over several lines — a formatter that puts
        // the parameters of a two-argument function on their own lines leaves the
        // value on the line that closes the parameter list — and a per-line scan
        // sees only the fragment `fun checklistItem(`, which holds no value and no
        // name, so the declaration silently disappears from the catalogue.
        //
        // A chunk ends where the parentheses balance again, because a parameter
        // list is the only thing in this file that spans lines: a constant is
        // always a single line, and a function's value follows its closing
        // parenthesis.
        val codeOnly = KotlinText.stripCode(source).lines()
        var index = 0
        while (index < lines.size) {
            val start = index
            val chunk = StringBuilder()
            var end = index
            var balance = 0
            while (end < lines.size) {
                chunk.append(lines[end]).append('\n')
                end++
                balance += parenDelta(codeOnly, end - 1)
                // A parameter list is still open: the declaration continues.
                if (balance > 0) continue
                if (declRegex.containsMatchIn(chunk)) break
                // The declaration is complete but this regex does not describe
                // it — unless the value sits on the next line.
                val next = lines.getOrNull(end)
                if (next == null || !next.trimStart().startsWith("=")) break
            }

            if (rootDepth != null && start > rootLine) {
                declRegex.find(chunk)?.let { found ->
                    val owners = ownersByLine[start].orEmpty()
                    result.add(build((owners + found.groupValues[1]).joinToString("."), found.groupValues[2]))
                }
            }
            index = end
        }
        return result
    }

    /**
     * The number of parentheses opened minus the number closed, on one line of a
     * source whose literal contents are already blanked.
     *
     * The blanked text is what makes the count trustworthy: a parenthesis inside
     * a string literal is not a parenthesis of the code.
     */
    private fun parenDelta(
        codeOnly: List<String>,
        index: Int,
    ): Int {
        var delta = 0
        for (ch in codeOnly.getOrElse(index) { "" }) {
            when (ch) {
                '(' -> delta++
                ')' -> delta--
            }
        }
        return delta
    }

    /**
     * Resolves [TEST_TAGS_SOURCE] to an absolute path using the `commonMain.root`
     * system property (set in `shared/build.gradle.kts`).
     *
     * `commonMain.root` points to `src/commonMain/kotlin`; three parent traversals
     * give the `:shared` module root, from which [TEST_TAGS_SOURCE] is relative.
     */
    private fun readTestTagsSource(): String {
        val commonMainRoot =
            System.getProperty("commonMain.root")
                ?: error("commonMain.root system property is not set — see shared/build.gradle.kts")
        val moduleRoot =
            Paths
                .get(commonMainRoot)
                .parent.parent.parent
        return Files.readString(moduleRoot.resolve(TEST_TAGS_SOURCE))
    }
}
