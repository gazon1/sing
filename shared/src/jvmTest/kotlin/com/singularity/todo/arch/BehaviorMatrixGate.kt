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
 * three test classes, and a row that names `DraftMviEffectTest` must not be
 * satisfied by a test of `DraftMviViewModelTest` that happens to live in the
 * same file.
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
     * A class that cannot be found is simply absent from the map — a caller
     * reads that as "no such class here", not as "an empty class".
     */
    fun classBodies(source: String): Map<String, String> {
        val code = KotlinText.stripCode(source)
        return classDeclaration
            .findAll(code)
            .mapNotNull { declaration ->
                val body = classBody(code, declaration.range.last + 1) ?: return@mapNotNull null
                declaration.groupValues[1] to body
            }.distinctBy { it.first }
            .toMap()
    }

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
     */
    private fun classBody(
        code: String,
        afterName: Int,
    ): String? {
        var index = afterName
        while (index < code.length && code[index].isWhitespace()) index++
        if (index < code.length && code[index] == '(') {
            val close = SourceScan.closingParen(code, index)
            if (close < 0) return null
            index = close + 1
            while (index < code.length && code[index].isWhitespace()) index++
        }
        if (index >= code.length || code[index] != '{') return null
        val close = KotlinText.matchingBrace(code, index)
        if (close < 0) return null
        return code.substring(index + 1, close)
    }

    /** The name inside the backticks of a matrix cell. */
    private val backticked = Regex("`([^`]+)`")

    /** A test annotation — `kotlin.test.Test` or a qualified variant of it. */
    private val testAnnotation = Regex("""@(?:[A-Za-z0-9_]+\.)?Test\b""")

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
