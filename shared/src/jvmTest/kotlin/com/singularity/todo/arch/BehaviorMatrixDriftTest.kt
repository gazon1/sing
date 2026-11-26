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
 * in the wrong file is still a finding — and a class that no longer exists is
 * a finding as well, not a row that quietly falls back to the matrix file.
 *
 * The rule itself lives in [BehaviorMatrixGate], and
 * [BehaviorMatrixGateSelfTest] drives it with synthetic sources.
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

    @Test
    fun `every row of the behavior matrix names a test that exists`() {
        val matrixSource = matrixFile.readText()
        val rows = BehaviorMatrixGate.rows(matrixSource)
        assertTrue(rows.isNotEmpty(), "the matrix in ${matrixFile.path} has no rows to check")

        val missing = BehaviorMatrixGate.missingRows(rows, matrixSource, ::ownerSource)
        assertTrue(
            missing.isEmpty(),
            "these rows of the behavior matrix name a test that does not exist:\n" +
                missing.joinToString("\n") { "  ${it.line.trim()}" } +
                "\n\nA row that names a test which is gone claims a guarantee that is no longer " +
                "covered. Rename the row after the test, or point it at the test that holds the " +
                "guarantee now.",
        )
    }

    /**
     * The source of the file that declares [className], or null when this
     * module has no such class.
     *
     * Comments are stripped before the declaration is looked for, so a class
     * mentioned in a KDoc cannot pass for a declaration.
     */
    private fun ownerSource(className: String): String? =
        testSources
            .firstOrNull { file -> classDeclaration(className).containsMatchIn(stripped(file)) }
            ?.readText()

    private fun stripped(file: File): String = KotlinText.stripComments(file.readText())

    private companion object {
        /** A declaration of [className] — the owner of a class-qualified row. */
        fun classDeclaration(className: String): Regex = Regex("""\bclass\s+$className\b""")
    }
}
