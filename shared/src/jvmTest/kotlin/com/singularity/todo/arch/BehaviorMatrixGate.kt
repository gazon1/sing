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
 * A row names a test either bare (a test of the matrix's own file) or
 * class-qualified (`ClassName.test name`). Both are checked against the
 * functions that carry a test annotation:
 *
 * - a bare name is looked up in the matrix file;
 * - a class-qualified name is looked up in the file that declares that class,
 *   and a class that cannot be found is a missing row — never a row that falls
 *   back to the matrix file. The fallback would let a row survive the death of
 *   its owner class as long as the matrix file happens to hold a function of
 *   the same name.
 *
 * ## What reading sources costs
 *
 * No type resolution: this is a name check, and a name is what the table
 * holds. Comments are stripped with [KotlinText], so a `class Foo` mentioned in
 * a KDoc cannot pass for a declaration.
 */
internal object BehaviorMatrixGate {
    /**
     * One row of the matrix: the line it is written on, the class it names
     * (null when it names a test of the matrix's own file) and the test.
     */
    data class Row(val line: String, val className: String?, val testName: String)

    /**
     * The rows of the matrix written in [matrixSource].
     *
     * The table is written in a KDoc, so its lines carry the comment prefix;
     * a row is a line that starts with the table's column separator and names
     * something in backticks.
     */
    fun rows(matrixSource: String): List<Row> =
        matrixSource
            .lineSequence()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.startsWith("|") }
            .mapNotNull { line ->
                val entry = backticked.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
                Row(
                    line = line,
                    className = entry.substringBefore('.', "").takeIf { it.isNotEmpty() },
                    testName = entry.substringAfter('.', entry),
                )
            }.toList()

    /**
     * The names of the functions of [source] that carry a test annotation.
     *
     * A function that lost its `@Test` is no longer a test, and a row that
     * names it claims coverage that nothing runs any more.
     */
    fun testFunctionNames(source: String): Set<String> {
        val stripped = KotlinText.stripComments(source)
        return testAnnotation
            .findAll(stripped)
            .mapNotNull { annotation ->
                val tail = stripped.substring(annotation.range.last + 1)
                val declaration =
                    functionDeclaration.find(tail)?.takeIf { it.range.first <= LOOKAHEAD }
                declaration?.groupValues?.get(1)?.trim('`')
            }.toSet()
    }

    /**
     * The rows that do not name a test that exists.
     *
     * [ownerSource] returns the source of the file that declares a class, or
     * null when this tree has no such class — and a qualified row with no
     * owner class is missing, never resolved against [matrixSource].
     */
    fun missingRows(
        rows: List<Row>,
        matrixSource: String,
        ownerSource: (String) -> String?,
    ): List<Row> =
        rows.filter { row ->
            val source: String? =
                if (row.className != null) ownerSource(row.className) else matrixSource
            source == null || row.testName !in testFunctionNames(source)
        }

    /** The name inside the backticks of a matrix cell. */
    private val backticked = Regex("`([^`]+)`")

    /** A test annotation — `kotlin.test.Test` or a qualified variant of it. */
    private val testAnnotation = Regex("""@(?:[A-Za-z0-9_]+\.)?Test\b""")

    /** A function declaration, with the name it declares. */
    private val functionDeclaration = Regex("""\bfun\s+(?:<[^>]*>\s*)?(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)""")

    /** How far past a test annotation a declaration may start. */
    private const val LOOKAHEAD = 200
}
