package com.singularity.todo.arch

/**
 * One character walk over Kotlin source, and every operation built on it.
 *
 * ## The model: three kinds of character
 *
 * The walk classifies every character of a file as exactly one of:
 *
 * - **code** — a character that can be a brace, and therefore can change the
 *   structure of the file;
 * - **comment** — the text of a line or block comment;
 * - **literal** — the *contents* of a string or character literal, and the
 *   delimiters of a string template (`${` and its matching `}`).
 *
 * Everything else follows from that classification: [stripComments] blanks the
 * comments, [stripCode] blanks the comments and the literal contents, and
 * [matchingBrace] counts braces **in code only**.
 *
 * ## Why the classification exists
 *
 * A brace counter over raw source counts braces that are not braces. A string
 * holding `"{"`, a KDoc mentioning a `{`, a raw string holding a JSON fixture —
 * each shifts the depth, and the block they belong to ends in the wrong place.
 *
 * Measured on this repository: a scanner that blanks comments but leaves
 * literals visible disagrees with a full lexer about the brace depth in **444 of
 * 1650** Kotlin files (27 %), and extracts **103 function bodies** in 27 files
 * of `:shared` to the wrong end. Every rule that reads a body — "does this
 * function drop a `Result`", "does this class declare a test" — inherits the
 * error, silently.
 *
 * ## Why one walk and not five
 *
 * Before this, the repository had five ways to strip comments from Kotlin
 * source: this file's predecessor in [SourceScan], two private copies in
 * `CachedIdentityReadArchitectureTest` and `CrashReportingWiringTest`, the
 * stripper in `RunnableTestMember`, and the oracle in
 * `ClassBodyScannerAgreementTest`. Five copies of a lexer is five sets of bugs
 * in it, and [SourceScan] already said so when it was written:
 * "Two copies of a comment stripper means two sets of bugs in it … extracted so
 * the third such rule does not write a third one."
 *
 * This object is the one lexer. The Python side of the same idea is
 * `scripts/src/scanners/source_text.py`, and the two are held to the same
 * answers by the fixture table in `config/test-fixtures/`.
 *
 * The one deliberate exception is the oracle in
 * `ClassBodyScannerAgreementTest`: it is a second implementation **on purpose**,
 * because a guard that compares an implementation with itself cannot fail. It is
 * a test double, not a third lexer in production use.
 *
 * ## The rules it follows
 *
 * - **A raw string wins over a plain one.** The three-quote form is matched
 *   before the one-quote form, so a raw string is never cut short at its first
 *   inner quote.
 * - **A run of four or more quotes** at the end of a raw string means the first
 *   N-3 are content and the last three close it. Taking the first three as the
 *   closer leaves a quote dangling, and the next quote in the file opens a
 *   string that never ends.
 * - **Block comments nest.** A comment that contains another comment ends at the
 *   *second* closing marker, not the first. A KDoc in this repository quotes a
 *   code sample that has a comment of its own, and a scanner that ends the
 *   comment at the first marker reads the rest of the KDoc as code, meets a
 *   quote inside it, and swallows the file.
 * - **An escape keeps the literal open.** `"a\"b"` ends at the second quote.
 * - **A template is code.** `"${run { 1 }}"` holds a brace that closes a real
 *   block, so the template's *body* is code, while its `${` and `}` are literal
 *   text and do not count. Treating the whole string as text loses two braces
 *   and ends the enclosing block in the wrong place; treating it as code counts
 *   the text around it.
 * - **An unterminated literal does not hang.** Every branch consumes at least
 *   one character, so an unbalanced quote costs a wrong answer, never a wedged
 *   gate.
 * - **Nothing is deleted.** Every removed character becomes a space, so the
 *   result has the same length as the input and an offset in the result still
 *   points at the same place in the source.
 */
internal object KotlinText {

    private const val TRIPLE = "\"\"\""

    /** The template opener, `${`. */
    private const val TEMPLATE = "\${"

    private const val CODE: Byte = 0
    private const val COMMENT: Byte = 1
    private const val LITERAL: Byte = 2

    /**
     * The file with its comments blanked out, one character per source character.
     *
     * Literal contents are left alone: a caller that wants to *read* a value out
     * of a string still can, and `//` inside a URL is not a comment.
     */
    fun stripComments(source: String): String {
        val kinds = Walk(source).run()
        return blank(source, kinds) { it == COMMENT }
    }

    /**
     * The file with its comments and its literal contents blanked out, one
     * character per source character.
     *
     * What is left is code, and only code can change the structure of the file.
     * A template's body is code and stays; the template's text does not.
     */
    fun stripCode(source: String): String {
        val kinds = Walk(source).run()
        return blank(source, kinds) { it != CODE }
    }

    /**
     * The index of the `}` that closes the `{` at [openIndex], or -1.
     *
     * Only braces in code are counted. A brace in a string, in a comment or in
     * the text of a template is not a brace of the file; a brace in a template's
     * *body* is, because it opens and closes a real block.
     */
    fun matchingBrace(source: String, openIndex: Int): Int {
        if (openIndex < 0 || openIndex >= source.length) return -1
        val kinds = Walk(source).run()
        var depth = 0
        for (i in openIndex until source.length) {
            if (kinds[i] != CODE) continue
            when (source[i]) {
                '{' -> depth++

                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /**
     * The index of the first `{` that is code, or -1.
     *
     * "Code" is the point: a `{` in a string, in a KDoc or in a template's text
     * does not open a block, and a caller that wants the first *block* must not
     * stop at one.
     */
    fun firstCodeBrace(source: String): Int {
        val kinds = Walk(source).run()
        for (i in source.indices) {
            if (kinds[i] == CODE && source[i] == '{') return i
        }
        return -1
    }

    /**
     * The text inside the first code block, or null when it is never closed.
     *
     * The braces themselves are not part of it.
     */
    fun blockBody(source: String): String? {
        val open = firstCodeBrace(source)
        if (open < 0) return null
        val close = matchingBrace(source, open)
        if (close < 0) return null
        return source.substring(open + 1, close)
    }

    /**
     * The brace depth at the end of each line, one entry per line.
     *
     * Only code braces are counted, so a line holding a string with a brace in
     * it does not change the depth of the lines that follow it.
     */
    fun lineDepths(source: String): IntArray {
        val kinds = Walk(source).run()
        val depths = mutableListOf<Int>()
        var depth = 0
        for (i in source.indices) {
            if (kinds[i] == CODE) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
            }
            if (source[i] == '\n') depths.add(depth)
        }
        if (!source.endsWith("\n")) depths.add(depth)
        return depths.toIntArray()
    }

    // ── the walk ──────────────────────────────────────────────────────────────

    /**
     * The character walk itself: one pass, one classification per character.
     *
     * The kinds buffer starts as code, so only the characters that are *not* code
     * are written to it.
     */
    private class Walk(private val source: String) {

        private val kinds = ByteArray(source.length)
        private var index = 0

        fun run(): ByteArray {
            while (index < source.length) {
                when {
                    source.startsWith("//", index) -> lineComment()
                    source.startsWith("/*", index) -> blockComment()
                    source.startsWith(TRIPLE, index) -> rawString()
                    source[index] == '"' -> plainString()
                    source[index] == '\'' -> charLiteral()
                    else -> index++
                }
            }
            return kinds
        }

        private fun lineComment() {
            val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
            mark(index, end, COMMENT)
            index = end
        }

        private fun blockComment() {
            val end = endOfBlockComment(index)
            mark(index, end, COMMENT)
            index = end
        }

        /**
         * The index just past the closing marker of the block comment at [start].
         *
         * Kotlin block comments nest: the outer one ends at the *second* closing
         * marker, not the first. A KDoc in this repository quotes a code sample
         * that contains a comment of its own, and a scanner that ends the comment
         * at the first marker reads the rest of the KDoc as code, meets a quote
         * inside it, and swallows the file.
         */
        private fun endOfBlockComment(start: Int): Int {
            var depth = 0
            var i = start
            while (i < source.length) {
                val opens = source.startsWith("/*", i)
                val closes = !opens && source.startsWith("*/", i)
                val step = if (opens || closes) 2 else 1
                if (opens) {
                    depth++
                } else if (closes) {
                    depth--
                }
                i += step
                if (closes && depth == 0) return i
            }
            return source.length
        }

        private fun rawString() {
            var i = index + 3
            var contentStart = i
            while (i < source.length) {
                when {
                    source.startsWith(TEMPLATE, i) -> {
                        mark(contentStart, i, LITERAL)
                        mark(i, i + 2, LITERAL)
                        index = i + 2
                        i = templateBody()
                        contentStart = i
                    }

                    source.startsWith(TRIPLE, i) -> {
                        var run = i
                        while (run < source.length && source[run] == '"') run++
                        if (run - i > 3) {
                            // More than three quotes: the last three of the run
                            // close the string, the rest is content.
                            mark(contentStart, run - 3, LITERAL)
                            index = run
                        } else {
                            mark(contentStart, i, LITERAL)
                            index = i + 3
                        }
                        return
                    }

                    else -> i++
                }
            }
            mark(contentStart, source.length, LITERAL)
            index = source.length
        }

        private fun plainString() {
            var i = index + 1
            var contentStart = i
            while (i < source.length) {
                val ch = source[i]
                when {
                    ch == '\\' -> i += 2

                    ch == '"' -> {
                        mark(contentStart, i, LITERAL)
                        index = i + 1
                        return
                    }

                    ch == '\n' -> {
                        // A plain string literal does not span lines in Kotlin.
                        mark(contentStart, i, LITERAL)
                        index = i
                        return
                    }

                    source.startsWith(TEMPLATE, i) -> {
                        mark(contentStart, i, LITERAL)
                        mark(i, i + 2, LITERAL)
                        index = i + 2
                        i = templateBody()
                        contentStart = i
                    }

                    else -> i++
                }
            }
            mark(contentStart, source.length, LITERAL)
            index = source.length
        }

        private fun charLiteral() {
            var i = index + 1
            val contentStart = i
            while (i < source.length) {
                val ch = source[i]
                when {
                    ch == '\\' -> i += 2

                    ch == '\'' -> {
                        mark(contentStart, i, LITERAL)
                        index = i + 1
                        return
                    }

                    ch == '\n' -> {
                        mark(contentStart, i, LITERAL)
                        index = i
                        return
                    }

                    else -> i++
                }
            }
            mark(contentStart, source.length, LITERAL)
            index = source.length
        }

        /**
         * The body of a string template: code until the `}` that closes it.
         *
         * The template's delimiters are literal text and its body is code, so the
         * braces in the body are counted and the closing `}` is not.
         */
        private fun templateBody(): Int {
            var depth = 0
            while (index < source.length) {
                val ch = source[index]
                when {
                    source.startsWith("//", index) -> lineComment()

                    source.startsWith("/*", index) -> blockComment()

                    source.startsWith(TRIPLE, index) -> rawString()

                    ch == '"' -> plainString()

                    ch == '\'' -> charLiteral()

                    ch == '{' -> {
                        depth++
                        index++
                    }

                    ch == '}' -> {
                        if (depth == 0) {
                            mark(index, index + 1, LITERAL)
                            index++
                            return index
                        }
                        depth--
                        index++
                    }

                    else -> index++
                }
            }
            return index
        }

        private fun mark(start: Int, end: Int, kind: Byte) {
            for (i in start until minOf(end, kinds.size)) kinds[i] = kind
        }
    }

    /**
     * The source with every character the predicate selects replaced by a space.
     *
     * Newlines survive, so a line number from the result still points at the
     * same line of the source, and in-line offsets do not move either.
     */
    private fun blank(source: String, kinds: ByteArray, predicate: (Byte) -> Boolean): String {
        val out = source.toCharArray()
        var start = -1
        for (i in kinds.indices) {
            val selected = predicate(kinds[i])
            if (selected && start < 0) {
                start = i
            } else if (!selected && start >= 0) {
                blankRange(out, start, i)
                start = -1
            }
        }
        if (start >= 0) blankRange(out, start, kinds.size)
        return String(out)
    }

    private fun blankRange(out: CharArray, start: Int, end: Int) {
        for (i in start until minOf(end, out.size)) {
            if (out[i] != '\n') out[i] = ' '
        }
    }
}
