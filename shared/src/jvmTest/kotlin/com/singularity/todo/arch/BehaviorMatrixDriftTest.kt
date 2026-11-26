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
 * of the class that carries the matrix, or `ClassName.test name` — the form
 * the rows of the subclasses use, because those tests live in other classes.
 * A class-qualified name is checked in the class it names, so a name that
 * exists in the wrong class is still a finding — and a class that no longer
 * exists is a finding as well, not a row that quietly falls back to the
 * matrix file.
 *
 * A row that names no test at all is a finding too: a table line that lost
 * its test name would otherwise drop out of the check, and the table would
 * claim a guarantee nothing verifies.
 *
 * A class name is not an identity either. Two packages may hold a class of
 * the same name, and a row that names such a class is a finding rather than a
 * guess: the gate would have to pick one of the two, and a wrong pick turns a
 * missing test into a green gate.
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

    /** The class whose KDoc carries the matrix — the class a bare row names. */
    private val matrixClass: String = "DraftMviViewModelTest"

    /** Every test source of this module — `commonTest` compiles into `jvmTest`. */
    private val testSources: List<File> by lazy {
        listOf(SourceScan.commonTestSourcesRoot(), SourceScan.jvmTestSourcesRoot())
            .flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
            }
    }

    /** The bodies of every test class of this module, by class name. */
    private val classBodies: Map<String, List<String>> by lazy {
        val bodies = mutableMapOf<String, MutableList<String>>()
        for (file in testSources) {
            for ((name, body) in BehaviorMatrixGate.classBodies(file.readText())) {
                bodies.getOrPut(name) { mutableListOf() }.add(body)
            }
        }
        bodies
    }

    @Test
    fun `every row of the behavior matrix names a test that exists`() {
        val matrix = BehaviorMatrixGate.parse(matrixFile.readText())
        assertTrue(matrix.rows.isNotEmpty(), "the matrix in ${matrixFile.path} has no rows to check")
        assertTrue(
            matrix.malformed.isEmpty(),
            "these lines of the behavior matrix name no test at all:\n" +
                matrix.malformed.joinToString("\n") { "  ${it.trim()}" } +
                "\n\nA row without a test name checks nothing, and the table would claim a " +
                "guarantee that nothing verifies.",
        )

        val ambiguous = BehaviorMatrixGate.ambiguousClassNames(classBodies)
        val confused = matrix.rows.filter { it.className != null && it.className in ambiguous }
        assertTrue(
            confused.isEmpty(),
            "these rows of the behavior matrix name a class that more than one package claims:\n" +
                confused.joinToString("\n") { "  ${it.line.trim()}" } +
                "\n\nA class name is not an identity: the gate cannot know which of the classes the " +
                "row means, so it counts neither of them as the owner.",
        )

        val missing =
            BehaviorMatrixGate.missingRows(
                rows = matrix.rows,
                matrixClassBody = BehaviorMatrixGate.ownerBody(classBodies, matrixClass),
                ownerBody = { BehaviorMatrixGate.ownerBody(classBodies, it) },
            )
        assertTrue(
            missing.isEmpty(),
            "these rows of the behavior matrix name a test that does not exist:\n" +
                missing.joinToString("\n") { "  ${it.line.trim()}" } +
                "\n\nA row that names a test which is gone claims a guarantee that is no longer " +
                "covered. Rename the row after the test, or point it at the test that holds the " +
                "guarantee now.",
        )
    }
}
