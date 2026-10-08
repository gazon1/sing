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
     */
    fun bodyOf(source: String, openIndex: Int): String? {
        if (openIndex < 0 || openIndex >= source.length || source[openIndex] != '{') return null
        var depth = 0
        var i = openIndex
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++

                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openIndex + 1, i)
                }
            }
            i++
        }
        return null
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
     * Declarations are found by scanning [source]; the body is extracted from [stripComments]
     * so regexes run against it are comment-safe. Line numbers are 1-based.
     */
    fun methods(source: String): List<Method> {
        val stripped = stripComments(source)
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

    /**
     * Blanks comments, preserving line numbers and in-line offsets.
     *
     * Newlines survive so a finding still points at the right line; every other character
     * becomes a space so offsets inside a line do not shift either. String and char literals
     * are left alone, because `//` inside a URL is not a comment and blanking it would corrupt
     * the line it sits on.
     *
     * Structured as a mode loop over four delegated steps rather than one `when` of ten states:
     * as a single function this was 23 branches against detekt's limit of 20, which is the
     * honest measure of "a lexer in a `when`".
     */
    fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var mode = Mode.CODE
        while (i < source.length) {
            val step: Pair<Mode, Int> = when (mode) {
                Mode.CODE -> codeStep(source, i, out)
                Mode.LINE -> lineStep(source, i, out)
                Mode.BLOCK -> blockStep(source, i, out)
                Mode.STRING, Mode.CHAR -> literalStep(source, i, out, mode)
            }
            mode = step.first
            i = step.second
        }
        return out.toString()
    }

    /** One character of code: copied verbatim, or the opener of a comment or a literal. */
    private fun codeStep(source: String, i: Int, out: StringBuilder): Pair<Mode, Int> {
        val c = source[i]
        val next = source.getOrNull(i + 1)
        return when {
            c == '/' && next == '/' -> {
                out.append("  ")
                Mode.LINE to i + 2
            }

            c == '/' && next == '*' -> {
                out.append("  ")
                Mode.BLOCK to i + 2
            }

            c == '"' -> {
                out.append(c)
                Mode.STRING to i + 1
            }

            c == '\'' -> {
                out.append(c)
                Mode.CHAR to i + 1
            }

            else -> {
                out.append(c)
                Mode.CODE to i + 1
            }
        }
    }

    /** Inside a line comment: everything is blanked until the newline, which is copied. */
    private fun lineStep(source: String, i: Int, out: StringBuilder): Pair<Mode, Int> {
        val c = source[i]
        return if (c == '\n') {
            out.append(c)
            Mode.CODE to i + 1
        } else {
            out.append(' ')
            Mode.LINE to i + 1
        }
    }

    /** Inside a block comment: blanked, and the run ends at its closing delimiter. */
    private fun blockStep(source: String, i: Int, out: StringBuilder): Pair<Mode, Int> {
        val c = source[i]
        val next = source.getOrNull(i + 1)
        return when {
            c == '\n' -> {
                out.append(c)
                Mode.BLOCK to i + 1
            }

            c == '*' && next == '/' -> {
                out.append("  ")
                Mode.CODE to i + 2
            }

            else -> {
                out.append(' ')
                Mode.BLOCK to i + 1
            }
        }
    }

    /**
     * Inside a string or char literal: copied, because `//` inside a URL is not a comment.
     *
     * The escaped character after a backslash is copied with it, so an escaped quote does
     * not end the literal early — which would otherwise leave the rest of the line treated
     * as code and blank out a URL.
     */
    private fun literalStep(
        source: String,
        i: Int,
        out: StringBuilder,
        mode: Mode,
    ): Pair<Mode, Int> {
        val c = source[i]
        val closes = (mode == Mode.STRING && c == '"') || (mode == Mode.CHAR && c == '\'')
        return when {
            c == '\\' -> {
                out.append(c)
                source.getOrNull(i + 1)?.let { out.append(it) }
                mode to i + 2
            }

            closes -> {
                out.append(c)
                Mode.CODE to i + 1
            }

            else -> {
                out.append(c)
                mode to i + 1
            }
        }
    }

    private enum class Mode { CODE, LINE, BLOCK, STRING, CHAR }
}
