package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The positive control of [BehaviorMatrixDriftTest].
 *
 * ## Why this exists
 *
 * A gate that is only ever run against the real tree proves nothing about
 * itself: as long as the tree is consistent, the gate is green, and a gate
 * that cannot report a defect cannot be trusted to report one. The first
 * version of this gate had exactly that shape — it read the matrix, looked
 * the names up, and was green on the tree it was written for.
 *
 * So the rule is driven here with synthetic sources, one defect per test:
 * a missing owner class, a function that lost its test annotation, the
 * fallback that a missing owner class used to hide behind, a test that
 * belongs to another class of the same file, an annotation and a declaration
 * that exist only inside a string, and a table line that names no test.
 *
 * ## What a green run here means
 *
 * Each test states the defect it injects and expects the gate to report it.
 * A change that weakens the rule — dropping the annotation check, restoring
 * the fallback to the matrix file, widening the search to the whole file —
 * fails here, not three commits later when somebody renames a test.
 */
@Tag("fast")
class BehaviorMatrixGateSelfTest {
    @Test
    fun `a row that names an annotated test in its owner class is covered`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner = owner(annotated = true)

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertTrue(missing.isEmpty(), "an annotated test in the owner class is covered: $missing")
    }

    @Test
    fun `a row that names a function without a test annotation is missing`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner = owner(annotated = false)

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertEquals(1, missing.size, "a function that is not a test covers nothing: $missing")
    }

    @Test
    fun `a row whose owner class is gone does not fall back to the matrix file`() {
        val matrix =
            "class Matrix {\n    @Test\n    fun `a test`() {}\n}\n" +
                matrix("| a | `Gone.a test` |")
        val owner = owner(annotated = true)

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertEquals(
            1,
            missing.size,
            "a row whose owner class is gone is missing even when the matrix file holds a " +
                "function of the same name: $missing",
        )
    }

    @Test
    fun `a bare row is checked against the class that carries the matrix`() {
        val matrix =
            "class Matrix {\n    @Test\n    fun `a test`() {}\n}\n" +
                matrix("| a | `a test` |")

        val missing = missing(matrix, matrixClassBody = body(matrix, "Matrix"), owner = null)

        assertTrue(missing.isEmpty(), "a bare row names a test of the matrix's class: $missing")
    }

    @Test
    fun `a bare row that names a function without a test annotation is missing`() {
        val matrix =
            "class Matrix {\n    fun `a test`() {}\n}\n" +
                matrix("| a | `a test` |")

        val missing = missing(matrix, matrixClassBody = body(matrix, "Matrix"), owner = null)

        assertEquals(1, missing.size, "a function that is not a test covers nothing: $missing")
    }

    @Test
    fun `a row is not satisfied by a test of another class of the same file`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner {\n}\n" +
                "class Other {\n    @Test\n    fun `a test`() {}\n}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertEquals(
            1,
            missing.size,
            "a test of another class does not cover a row that names this class: $missing",
        )
    }

    @Test
    fun `an annotation and a declaration inside a string are not a test`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner {\n" +
                "    private val sample = \"@Test\\nfun `a test`() {}\"\n" +
                "}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertEquals(
            1,
            missing.size,
            "an annotation written inside a string is not an annotation: $missing",
        )
    }

    @Test
    fun `a table line that names no test is malformed`() {
        val matrix = matrix("| a row without a test |")

        val parsed = BehaviorMatrixGate.parse(matrix)

        assertEquals(1, parsed.malformed.size, "a row without a test name is a defect: $matrix")
        assertTrue(parsed.rows.isEmpty(), "a malformed row is not a row: ${parsed.rows}")
    }

    @Test
    fun `a class that extends another class is found`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner : Base() {\n" +
                "    @Test\n    fun `a test`() {}\n" +
                "}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertTrue(missing.isEmpty(), "a class with a supertype is still a class: $missing")
    }

    @Test
    fun `a class with type parameters and a supertype is found`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner<T> : Base<T>() {\n" +
                "    @Test\n    fun `a test`() {}\n" +
                "}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertTrue(missing.isEmpty(), "a generic class with a generic supertype is still a class: $missing")
    }

    /** The rows of [matrix] that do not name a test that exists. */
    private fun missing(
        matrix: String,
        matrixClassBody: String?,
        owner: String?,
    ): List<BehaviorMatrixGate.Row> {
        val parsed = BehaviorMatrixGate.parse(matrix)
        val bodies = owner?.let { BehaviorMatrixGate.classBodies(it) } ?: emptyMap()
        return BehaviorMatrixGate.missingRows(
            rows = parsed.rows,
            matrixClassBody = matrixClassBody,
            ownerBody = { bodies[it] },
        )
    }

    /** The body of the class [className] of [source]. */
    private fun body(
        source: String,
        className: String,
    ): String? = BehaviorMatrixGate.classBodies(source)[className]

    /** The source of a class that declares `a test`, with or without a test annotation. */
    private fun owner(annotated: Boolean): String {
        val annotation = if (annotated) "    @Test\n" else ""
        return "class Owner {\n$annotation    fun `a test`() {}\n}\n"
    }

    /** A matrix source with one row. */
    private fun matrix(row: String): String = "| Row | Test |\n|--|--|\n$row\n"
}
