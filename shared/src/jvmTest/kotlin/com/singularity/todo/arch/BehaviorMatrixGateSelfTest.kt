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
 * a missing owner class, a function that lost its test annotation, and the
 * fallback that a missing owner class used to hide behind.
 *
 * ## What a green run here means
 *
 * Each test states the defect it injects and expects the gate to report it.
 * A change that weakens the rule — dropping the annotation check, restoring
 * the fallback to the matrix file — fails here, not three commits later when
 * somebody renames a test.
 */
@Tag("fast")
class BehaviorMatrixGateSelfTest {
    @Test
    fun `a row that names an annotated test in its owner class is covered`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner = owner(annotated = true)

        val missing =
            BehaviorMatrixGate.missingRows(BehaviorMatrixGate.rows(matrix), matrix) { ownerOf(it, owner) }

        assertTrue(missing.isEmpty(), "an annotated test in the owner class is covered: $missing")
    }

    @Test
    fun `a row that names a function without a test annotation is missing`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner = owner(annotated = false)

        val missing =
            BehaviorMatrixGate.missingRows(BehaviorMatrixGate.rows(matrix), matrix) { ownerOf(it, owner) }

        assertEquals(1, missing.size, "a function that is not a test covers nothing: $missing")
    }

    @Test
    fun `a row whose owner class is gone does not fall back to the matrix file`() {
        val matrix =
            "class Matrix {\n    @Test\n    fun `a test`() {}\n}\n" +
                matrix("| a | `Gone.a test` |")
        val owner = owner(annotated = true)

        val missing =
            BehaviorMatrixGate.missingRows(BehaviorMatrixGate.rows(matrix), matrix) { ownerOf(it, owner) }

        assertEquals(
            1,
            missing.size,
            "a row whose owner class is gone is missing even when the matrix file holds a " +
                "function of the same name: $missing",
        )
    }

    @Test
    fun `a bare row is checked against the matrix file`() {
        val matrix =
            "class Matrix {\n    @Test\n    fun `a test`() {}\n}\n" +
                matrix("| a | `a test` |")

        val missing = BehaviorMatrixGate.missingRows(BehaviorMatrixGate.rows(matrix), matrix) { null }

        assertTrue(missing.isEmpty(), "a bare row names a test of the matrix file: $missing")
    }

    @Test
    fun `a bare row that names a function without a test annotation is missing`() {
        val matrix =
            "class Matrix {\n    fun `a test`() {}\n}\n" +
                matrix("| a | `a test` |")

        val missing = BehaviorMatrixGate.missingRows(BehaviorMatrixGate.rows(matrix), matrix) { null }

        assertEquals(1, missing.size, "a function that is not a test covers nothing: $missing")
    }

    /** The source of the file that declares `Owner` — the only class in these sources. */
    private fun ownerOf(
        className: String,
        owner: String,
    ): String? = owner.takeIf { className == "Owner" }

    /** The source of a class that declares `a test`, with or without a test annotation. */
    private fun owner(annotated: Boolean): String {
        val annotation = if (annotated) "    @Test\n" else ""
        return "class Owner {\n$annotation    fun `a test`() {}\n}\n"
    }

    /** A matrix source with one row. */
    private fun matrix(row: String): String = "| Row | Test |\n$row\n"
}
