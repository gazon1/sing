package com.singularity.todo.core.ui

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
        var depth = 0
        // Depth of the `object TestTags` block itself; nothing outside it is a tag.
        var rootDepth: Int? = null

        for (rawLine in source.lines()) {
            val code = rawLine.substringBefore("//")

            OBJECT_REGEX.find(code)?.let { found ->
                if (rootDepth == null && found.groupValues[1] == "TestTags") {
                    rootDepth = depth
                }
                objectStack.addLast(depth to found.groupValues[1])
            }

            declRegex.find(code)?.let { found ->
                if (rootDepth != null) {
                    // Enclosing objects = everything on the stack opened below `object TestTags`.
                    val owners = objectStack.filter { it.first > rootDepth }.map { it.second }
                    result.add(build((owners + found.groupValues[1]).joinToString("."), found.groupValues[2]))
                }
            }

            depth += code.count { it == '{' }
            depth -= code.count { it == '}' }
            // An object opened at depth d is closed once depth falls back to d.
            while (objectStack.isNotEmpty() && objectStack.last().first >= depth) {
                objectStack.removeLast()
            }
        }
        return result
    }

    /**
     * Resolves [TEST_TAGS_SOURCE] to an absolute path using the `commonMain.root`
     * system property (set in `shared/build.gradle.kts`).
     *
     * `commonMain.root` points to `src/commonMain/kotlin`; three parent traversals
     * give the `:shared` module root, from which [TEST_TAGS_SOURCE] is relative.
     */
    private fun readTestTagsSource(): String {
        val commonMainRoot = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see shared/build.gradle.kts")
        val moduleRoot = Paths.get(commonMainRoot).parent.parent.parent
        return Files.readString(moduleRoot.resolve(TEST_TAGS_SOURCE))
    }
}
