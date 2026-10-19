package com.singularity.todo.arch

import java.io.File

/**
 * Shared plumbing for the tests that read Kotlin sources rather than a compiler.
 *
 * ## Why this exists
 *
 * `SyncedWriteEnqueuesTest` and `DroppedResultIsReportedTest` are the same kind of rule — a
 * lexical check over production `commonMain` — and each had grown its own brace matcher, file
 * walker and comment stripper. Two copies of a comment stripper means two sets of bugs in it,
 * and the second one appeared immediately: `DroppedResultIsReportedTest` reported the worked
 * example in `FeatureSlot`'s KDoc, because its stripper was written separately and handled the
 * edge cases differently.
 *
 * Extracted so the third such rule does not write a third one.
 *
 * ## Where the lexer lives
 *
 * The character walk that knows where the code ends is **[KotlinText]**, and
 * only there. This file is the plumbing around it: the roots, the file lists and
 * the two conveniences that read a function body. `KotlinText` used to live
 * here as a private copy; it is now the one implementation, shared with
 * `RunnableTestMember` and with the catalogue in `core/ui`.
 *
 * ## What reading sources costs
 *
 * No type resolution. A receiver's type is whatever its file declares it to be, and a method
 * returning `Result` is whatever the declaring interface says — both are read, not inferred.
 * That is enough to tell `TaskRepository.upsert` from `ChecklistRepository.upsert`, and it is
 * why the dropped-`Result` rule can exist at all: a name-based version is 9 for 9 wrong on this
 * tree.
 *
 * What it cannot do: see an inferred type, a generic helper, a `Result` consumed through
 * reflection, or a failure dropped in a loop where the value itself is used.
 */
internal object SourceScan {

    /** Root of `commonMain` for this module, as set by the `jvmTest` task. */
    fun commonMainRoot(): File {
        val root = System.getProperty("commonMain.root")
            ?: error("commonMain.root system property is not set — see the jvmTest task config")
        return File(root, "com/singularity/todo")
    }

    /**
     * Root of this module's `jvmTest` sources, as set by the `jvmTest` task.
     *
     * A property rather than `File("src/jvmTest/kotlin")`, because the task sets
     * `workingDir` to `build/allure-results` so the Allure listener writes where the
     * upload step looks — which made every relative path in every test resolve
     * against a build output directory. `SharedHarnessConventionsTest` walked a path
     * that no longer existed and reported green over zero files; the GenUI corpus
     * tests threw `FileNotFoundException`. Same reason as [commonMainRoot].
     */
    fun jvmTestSourcesRoot(): File = propertyRoot("jvmTestSources.root")

    /** Root of this module's `jvmTest` resources, as set by the `jvmTest` task. */
    fun jvmTestResourcesRoot(): File = propertyRoot("jvmTestResources.root")

    private fun propertyRoot(name: String): File {
        val root = System.getProperty(name)
            ?: error("$name system property is not set — see the jvmTest task config")
        return File(root)
    }

    /** Every production `.kt` file: `commonMain`, excluding the shared test doubles. */
    fun productionFiles(): List<File> =
        commonMainRoot().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filterNot { it.isTestDouble() }
            .toList()

    fun File.isTestDouble(): Boolean {
        val path = path.replace(File.separatorChar, '/')
        return path.contains("/test/fakes/") || path.contains("/test/helpers/")
    }

    /**
     * The `{ … }` body beginning at [openIndex], braces balanced, or null.
     *
     * Null when the braces do not balance, which is what an unbalanced source looks like; the
     * caller reads that as "cannot tell" rather than as "nothing here".
     *
     * The braces are matched by [KotlinText], so a brace inside a string, inside a
     * comment or inside the text of a string template does not end the body early.
     * Counting them here is what made **103 function bodies** in 27 files of
     * `:shared` end in the wrong place.
     */
    fun bodyOf(source: String, openIndex: Int): String? {
        if (openIndex < 0 || openIndex >= source.length || source[openIndex] != '{') return null
        val close = KotlinText.matchingBrace(source, openIndex)
        if (close < 0) return null
        return source.substring(openIndex + 1, close)
    }

    /** Index of the closing parenthesis matching the one at [openIndex], or -1. */
    fun closingParen(source: String, openIndex: Int): Int {
        var depth = 0
        var i = openIndex
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++

                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /**
     * The `{ … }` body of a function whose parameter list ends at [afterParenIndex].
     *
     * Unlike [bodyOf], this handles Kotlin functions with default parameters correctly:
     * `fun foo(x: Int = default()) { body }` — the `{` is not immediately after `)`.
     * Returns the text inside the braces (without the braces themselves), or null.
     */
    fun methodBody(source: String, afterParenIndex: Int): String? {
        var i = afterParenIndex + 1
        while (i < source.length && source[i].isWhitespace()) i++
        return bodyOf(source, i)
    }

    /**
     * Every function declaration in [source] with its stripped body.
     *
     * Declarations are found by scanning [source]; the body is extracted from the
     * comment-stripped source so regexes run against it are comment-safe. Line
     * numbers are 1-based.
     */
    fun methods(source: String): List<Method> {
        val stripped = KotlinText.stripComments(source)
        val declaration = Regex("""(?m)^[ \t]*(?:override |private |internal |suspend )*fun (\w+)\s*\(""")
        val starts = declaration.findAll(stripped).map { it.range.first to it.groupValues[1] }.toList()
        return starts.mapIndexed { index, (strippedOffset, name) ->
            val body = methodBodyFromStripped(stripped, strippedOffset) ?: ""
            val line = stripped.offsetToLine(strippedOffset)
            Method(name, line, body)
        }
    }

    /**
     * A `fun name(...)` block extracted from [source], with the line it starts on so a finding
     * can be jumped to. Line numbers are 1-based.
     */
    data class Method(val name: String, val line: Int, val body: String)

    // ── private helpers ─────────────────────────────────────────────────────────

    /**
     * The body of the function that starts at [funOffset] in [stripped], which must point to
     * the `fun` keyword. Scans forward to the `(` after the name, then to the `{`, then
     * extracts up to the matching `}`.
     *
     * Returns the text inside the braces, or null if the braces do not balance.
     */
    private fun methodBodyFromStripped(stripped: String, funOffset: Int): String? {
        var i = funOffset
        // Skip "fun <name>(" — the first '(' after "fun"
        while (i < stripped.length && stripped[i] != '(') i++
        if (i >= stripped.length) return null
        val closeParen = closingParen(stripped, i)
        if (closeParen < 0) return null
        // Find the opening brace
        var j = closeParen + 1
        while (j < stripped.length && stripped[j].isWhitespace()) j++
        if (j >= stripped.length || stripped[j] != '{') return null
        // Extract body using bodyOf on the stripped source
        return bodyOf(stripped, j)
    }

    private fun String.offsetToLine(offset: Int): Int =
        if (offset <= 0) 1 else substring(0, offset).count { it == '\n' } + 1
}
