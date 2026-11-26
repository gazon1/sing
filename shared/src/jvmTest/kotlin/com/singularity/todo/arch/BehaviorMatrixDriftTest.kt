package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The behavior matrix names a test in every row, and this gate proves the name
 * is a test that exists.
 *
 * ## What went wrong
 *
 * The matrix of the draft editor's guarantees lives in the KDoc of
 * `DraftMviViewModelTest` — one row per guarantee, and the test that holds it.
 * The table is prose: the names in it are text, and nothing checks them. A
 * developer renames a test and the table keeps claiming that the guarantee is
 * covered — by a test that no longer exists. The next reader of the matrix
 * inherits a claim nobody has checked, and the matrix is exactly the document
 * the parent issue reads to decide what may be simplified.
 *
 * ## What this gate reads
 *
 * The rows of the matrix, and the test sources they name. One direction is
 * enough: a row that names a test which does not exist is the defect. A test
 * that is not in the table is not a defect — the table lists guarantees, not
 * every test in the file.
 *
 * ## How a row is read
 *
 * A row's test is written in backticks. It is either the bare name of a test
 * in the matrix's own file, or `ClassName.test name` — the form the rows of the
 * subclasses use, because those tests live in other files. A class-qualified
 * name is checked in the file that declares the class, so a name that exists
 * in the wrong file is still a finding.
 *
 * ## What reading sources costs
 *
 * No type resolution: this is a name check, and a name is what the table
 * holds. The roots come from the build (`commonTestSources.root`,
 * `jvmTestSources.root`) rather than from the working directory — see
 * [SourceScan] for why a relative path here reports green over zero files.
 */
@Tag("fast")
class BehaviorMatrixDriftTest {
    /** The file that carries the matrix. */
    private val matrixFile: File =
        SourceScan
            .commonTestSourcesRoot()
            .resolve("com/singularity/todo/core/ui/DraftMviViewModelTest.kt")

    /** Every test source of this module — `commonTest` compiles into `jvmTest`. */
    private val testSources: List<File> by lazy {
        listOf(SourceScan.commonTestSourcesRoot(), SourceScan.jvmTestSourcesRoot())
            .flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
            }
    }

    /**
     * One row of the matrix: the line it is written on, the class it names
     * (null when it names a test of the matrix's own file) and the test.
     */
    private data class Row(val line: String, val className: String?, val testName: String)

    @Test
    fun `every row of the behavior matrix names a test that exists`() {
        val rows = matrixRows()
        assertTrue(rows.isNotEmpty(), "the matrix in ${matrixFile.path} has no rows to check")

        val missing = rows.filter { row -> !namesExistingTest(row) }
        assertTrue(
            missing.isEmpty(),
            "these rows of the behavior matrix name a test that does not exist:\n" +
                missing.joinToString("\n") { "  ${it.line.trim()}" } +
                "\n\nA row that names a test which is gone claims a guarantee that is no longer " +
                "covered. Rename the row after the test, or point it at the test that holds the " +
                "guaranteance now.",
        )
    }

    /**
     * The rows of the matrix, with the test each one names.
     *
     * The table is written in a KDoc, so its lines carry the comment prefix;
     * a row is a line that starts with the table's column separator.
     */
    private fun matrixRows(): List<Row> =
        matrixFile
            .readLines()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.startsWith("|") }
            .mapNotNull { line ->
                val entry = BACKTICKED.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
                Row(
                    line = line,
                    className = entry.substringBefore('.', "").takeIf { it.isNotEmpty() },
                    testName = entry.substringAfter('.', entry),
                )
            }

    /**
     * Whether the test the row names is declared where the row says it is.
     *
     * A bare name belongs to the matrix's own file; a class-qualified name
     * belongs to the file that declares that class.
     */
    private fun namesExistingTest(row: Row): Boolean {
        val source =
            row.className
                ?.let { ownerFile(it)?.readText() }
                ?: matrixFile.readText()
        return "fun $BACKTICK${row.testName}$BACKTICK" in source
    }

    /** The file that declares [className], if this module has one. */
    private fun ownerFile(className: String): File? =
        testSources.firstOrNull { file ->
            classDeclaration(className).containsMatchIn(file.readText())
        }

    private companion object {
        const val BACKTICK = '`'

        /** The name inside the backticks of a matrix cell. */
        val BACKTICKED = Regex("`([^`]+)`")

        /** A declaration of [className] — the owner of a class-qualified row. */
        fun classDeclaration(className: String): Regex = Regex("""\bclass\s+$className\b""")
    }
}
