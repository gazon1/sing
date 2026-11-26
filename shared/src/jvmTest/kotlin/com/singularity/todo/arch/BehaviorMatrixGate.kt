package com.singularity.todo.arch

/**
 * The rule of the behavior-matrix gate, without the file system.
 *
 * ## Why the rule lives apart from the test
 *
 * [BehaviorMatrixDriftTest] reads real files, so it can only ever prove that
 * *today's* tree is consistent. The failure it guards against — a row that
 * names a test which is gone, or a function that stopped being a test — is
 * about the rule itself, and a rule that can only be observed on the real tree
 * is a rule that is never observed. So the decision lives here, over plain
 * strings, and the self-test drives it with synthetic sources: a missing owner
 * class, a function without a test annotation, a bare name that only exists in
 * the matrix file.
 *
 * ## What a row is allowed to claim
 *
 * A row names a test either bare (a test of the class that carries the
 * matrix) or class-qualified (`ClassName.test name`). Both are checked
 * against the functions that carry a test annotation, *inside the class the
 * row names*:
 *
 * - a bare name is looked up in the class that holds the matrix;
 * - a class-qualified name is looked up in the class of that name, and a
 *   class that cannot be found is a missing row — never a row that falls
 *   back to the matrix file. The fallback would let a row survive the death
 *   of its owner class as long as the matrix file happens to hold a function
 *   of the same name.
 *
 * The class matters, not only the file: `DraftMviViewModelTest.kt` holds
 * several test classes, and a row that names `DraftMviEffectTest` must not be
 * satisfied by a test of `DraftMviViewModelTest` that happens to live in the
 * same file.
 *
 * The class name alone is not an identity either: two classes may share a
 * name — in different packages, or as two nested classes of one file — and a
 * row that names such a class resolves to nothing. The gate refuses to guess
 * which of the two classes the row means, because a wrong guess turns a
 * missing test into a green gate.
 *
 * ## What counts as a test
 *
 * A function carries a test annotation — `@Test`, or a qualified variant of
 * it. A function that lost its annotation is no longer a test, and a row
 * that names it claims coverage that nothing runs any more.
 *
 * ## What reading sources costs
 *
 * No type resolution: this is a name check, and a name is what the table
 * holds. Comments *and literal contents* are blanked with [KotlinText]
 * before anything is matched — a `class Foo` mentioned in a KDoc is not a
 * declaration, and a `@Test` written inside a string is not an annotation.
 */
internal object BehaviorMatrixGate {
    /**
     * One row of the matrix: the line it is written on, the class it names
     * (null when it names a test of the matrix's own class) and the test.
     */
    data class Row(val line: String, val className: String?, val testName: String)

    /**
     * A parsed matrix: the rows, and the lines of the table that name no
     * test at all.
     *
     * A row that names no test is a defect, not a row to skip: a table line
     * that lost its test name would otherwise vanish from the check, and the
     * table would claim a guarantee nothing verifies.
     */
    data class Matrix(val rows: List<Row>, val malformed: List<String>)

    /**
     * The rows of the matrix written in [matrixSource], and the table lines
     * that name no test.
     *
     * The table is written in a KDoc, so its lines carry the comment prefix;
     * a table line is a line that starts with the column separator. The
     * header of a table is the line right above the separator, and the
     * separator itself names no test.
     */
    fun parse(matrixSource: String): Matrix {
        val tableLines =
            matrixSource
                .lineSequence()
                .map { it.trim().removePrefix("*").trim() }
                .filter { it.startsWith("|") }
                .toList()
        val dataLines =
            tableLines
                .withIndex()
                .filterNot { (_, line) -> isSeparator(line) }
                .filterNot { (index, _) ->
                    index + 1 < tableLines.size && isSeparator(tableLines[index + 1])
                }.map { (_, line) -> line }
        val rows =
            dataLines.mapNotNull { line ->
                val entry = backticked.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
                Row(
                    line = line,
                    className = entry.substringBefore('.', "").takeIf { it.isNotEmpty() },
                    testName = entry.substringAfter('.', entry),
                )
            }
        val malformed = dataLines.filter { backticked.find(it) == null }
        return Matrix(rows, malformed)
    }

    /**
     * The bodies of the classes of [source], by class name.
     *
     * A name that more than one class claims holds more than one body — a
     * class that appears twice in the same file (two nested classes of the
     * same name, say) counts as often as it is declared. The gate refuses to
     * pick one of them, so the caller can see the ambiguity instead of
     * resolving a name by luck.
     *
     * A class without a body — `class Owner` — is a class that claims the
     * name too, so it is kept with an empty body rather than dropped: a name
     * that one bodied class and one bodyless class share is still a name two
     * classes claim.
     */
    fun classBodies(source: String): Map<String, List<String>> {
        val code = KotlinText.stripCode(source)
        return classDeclaration
            .findAll(code)
            .map { declaration ->
                val body = classBody(code, declaration.range.last + 1) ?: ""
                declaration.groupValues[1] to body
            }.groupBy({ it.first }, { it.second })
    }

    /**
     * The names that more than one class of [bodiesByName] claims.
     *
     * A class name is not an identity: two packages may hold a class of the
     * same name. A row that names such a class cannot be resolved — the gate
     * would have to pick one of the two, and a wrong pick turns a missing test
     * into a green gate.
     */
    fun ambiguousClassNames(bodiesByName: Map<String, List<String>>): Set<String> =
        bodiesByName.filterValues { it.size > 1 }.keys

    /**
     * The body of the class of [className], or null.
     *
     * Null when no class of that name exists — and also when more than one
     * class claims that name: an ambiguous name resolves to nothing, because
     * resolving it to one of the candidates would be a guess.
     */
    fun ownerBody(
        bodiesByName: Map<String, List<String>>,
        className: String,
    ): String? = bodiesByName[className]?.singleOrNull()

    /**
     * The names of the functions of [source] that carry a test annotation.
     *
     * Literal contents are blanked before the search, so an annotation and a
     * declaration written inside a string are not an annotation and not a
     * declaration.
     */
    fun testFunctionNames(source: String): Set<String> {
        val code = KotlinText.stripCode(source)
        return testAnnotation
            .findAll(code)
            .mapNotNull { annotation ->
                val tail = code.substring(annotation.range.last + 1)
                val declaration =
                    functionDeclaration.find(tail)?.takeIf { it.range.first <= LOOKAHEAD }
                declaration?.groupValues?.get(1)?.trim('`')
            }.toSet()
    }

    /**
     * The rows that do not name a test that exists.
     *
     * [matrixClassBody] is the body of the class that carries the matrix —
     * the class a bare row names. [ownerBody] returns the body of a class by
     * name, or null when this tree has no such class — and a qualified row
     * with no owner class is missing, never resolved against the matrix.
     */
    fun missingRows(
        rows: List<Row>,
        matrixClassBody: String?,
        ownerBody: (String) -> String?,
    ): List<Row> =
        rows.filter { row ->
            val body: String? =
                if (row.className != null) ownerBody(row.className) else matrixClassBody
            body == null || row.testName !in testFunctionNames(body)
        }

    /**
     * The body of the class whose name ends at [afterName], or null.
     *
     * [code] is already stripped of comments and literal contents, so a
     * brace in a string cannot end the body early.
     *
     * The header of a class is more than a name and a brace: a class may
     * carry type parameters (`class Owner<T>`), a primary constructor
     * (`class Owner(private val x: Int)`), a supertype list
     * (`class Owner : Base()`) and a `where` clause. The body is what
     * follows all of them, so the scan walks the header — balancing
     * parentheses and angle brackets — until it reaches the brace that opens
     * the body. A class whose header cannot be walked is not a class this
     * gate can judge, and is reported as "no such class".
     */
    private fun classBody(
        code: String,
        afterName: Int,
    ): String? {
        val open = bodyBrace(code, afterName) ?: return null
        val close = KotlinText.matchingBrace(code, open)
        return close.takeIf { it >= 0 }?.let { code.substring(open + 1, it) }
    }

    /**
     * The index of the brace that opens the body of the class, or null when
     * the header cannot be walked.
     */
    private fun bodyBrace(
        code: String,
        afterName: Int,
    ): Int? {
        val start = skipSpaces(code, afterName)
        val afterTypeParameters = skipTypeParameters(code, start)
        val afterConstructor = skipPrimaryConstructor(code, afterTypeParameters)
        return scanHeader(code, afterConstructor)
    }

    /**
     * Walks the rest of the class header until the brace that opens the body.
     *
     * A class without a body (`abstract class Owner`) would otherwise swallow
     * the body of the class that follows it — or the body of the function that
     * follows it — so the next class or function declaration ends the search
     * and the class is reported as "no such class".
     */
    private fun scanHeader(
        code: String,
        from: Int,
    ): Int? {
        var index = from
        while (index < code.length && code[index] != '{') {
            index = headerStep(code, index) ?: return null
        }
        return index.takeIf { index < code.length }
    }

    /**
     * The index past the group that starts at [index], or null when the
     * header cannot be walked any further.
     */
    private fun headerStep(
        code: String,
        index: Int,
    ): Int? {
        val char = code[index]
        if (char == '<' || char == '(') {
            val close =
                if (char == '<') {
                    matchingAngle(code, index)
                } else {
                    SourceScan.closingParen(code, index)
                }
            return close.takeIf { it >= 0 }?.plus(1)
        }
        if (startsWithWord(code, index, "class")) return null
        if (startsWithWord(code, index, "fun")) return null
        return index + 1
    }

    /** The index of the first non-space character at or after [index]. */
    private fun skipSpaces(
        code: String,
        index: Int,
    ): Int {
        var cursor = index
        while (cursor < code.length && code[cursor].isWhitespace()) cursor++
        return cursor
    }

    /**
     * The index after the type parameter list that starts at [index], or
     * [index] when there is none.
     */
    private fun skipTypeParameters(
        code: String,
        index: Int,
    ): Int {
        if (index >= code.length || code[index] != '<') return index
        val close = matchingAngle(code, index)
        return if (close < 0) index else close + 1
    }

    /**
     * The index after the primary constructor that starts at [index], or
     * [index] when there is none.
     */
    private fun skipPrimaryConstructor(
        code: String,
        index: Int,
    ): Int {
        val start = skipSpaces(code, index)
        if (start >= code.length || code[start] != '(') return start
        val close = SourceScan.closingParen(code, start)
        return if (close < 0) start else skipSpaces(code, close + 1)
    }

    /**
     * The index of the `>` that closes the `<` at [openIndex], or -1.
     *
     * Nested type arguments close with several `>` in a row, and a function
     * type closes with `->`, so a plain counter over `>` would end the list
     * too early.
     */
    private fun matchingAngle(
        code: String,
        openIndex: Int,
    ): Int {
        var depth = 0
        var index = openIndex
        while (index < code.length) {
            val char = code[index]
            val closesArrow = char == '>' && index > 0 && code[index - 1] == '-'
            if (char == '<') {
                depth++
            } else if (char == '>' && !closesArrow) {
                depth--
                if (depth == 0) return index
            }
            index++
        }
        return -1
    }

    /** Whether the word [word] starts at [index]. */
    private fun startsWithWord(
        code: String,
        index: Int,
        word: String,
    ): Boolean {
        if (!code.startsWith(word, index)) return false
        val after = index + word.length
        return after >= code.length || (!code[after].isLetterOrDigit() && code[after] != '_')
    }

    /** The name inside the backticks of a matrix cell. */
    private val backticked = Regex("`([^`]+)`")

    /** A test annotation — `kotlin.test.Test` or a qualified variant of it. */
    private val testAnnotation = Regex("""@(?:[A-Za-z0-9_]+\.)*Test\b""")

    /** A function declaration, with the name it declares. */
    private val functionDeclaration =
        Regex("""\bfun\s+(?:<[^>]*>\s*)?(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)""")

    /** A class declaration, with the name it declares. */
    private val classDeclaration = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)""")

    /** A table separator — dashes, colons and pipes only. */
    private val separator = Regex("""^[|\-:\s]+$""")

    /** How far past a test annotation a declaration may start. */
    private const val LOOKAHEAD = 200

    private fun isSeparator(line: String): Boolean = separator.matches(line)
}
