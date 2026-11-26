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
 * that exist only inside a string, a table line that names no test, and a
 * class name that two classes share.
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
    fun `a backticked row label is not the named test`() {
        // The backtick is in the label column: the Test column is empty, so the
        // row names no test — even though the line holds a backticked span that
        // happens to read like the name of a real test.
        val matrix = matrix("| `a test` | no test |")

        val parsed = BehaviorMatrixGate.parse(matrix)

        assertEquals(
            1,
            parsed.malformed.size,
            "the test is the last column, not the first backticked span: $matrix",
        )
        assertTrue(parsed.rows.isEmpty(), "a row with an empty test column is not a row: ${parsed.rows}")
    }

    @Test
    fun `the test column is read even when the label holds a backticked name`() {
        val matrix = matrix("| `label` | `Owner.a test` |")

        val parsed = BehaviorMatrixGate.parse(matrix)

        assertEquals(
            listOf("a test"),
            parsed.rows.map { it.testName },
            "the test is read from its own column, not from the label: $matrix",
        )
    }

    @Test
    fun `a table row that omits its outer pipes is still a row`() {
        // Markdown renders `guarantee | `test`` as a row of the table. Dropping
        // it for its missing leading pipe would take the row out of the check
        // without saying so.
        val matrix = matrix("initial draft | `Owner.a test`")

        val parsed = BehaviorMatrixGate.parse(matrix)

        assertEquals(
            listOf("a test"),
            parsed.rows.map { it.testName },
            "a row without outer pipes is a row: $matrix",
        )
        assertTrue(parsed.malformed.isEmpty(), "a row with a test is not malformed: ${parsed.malformed}")
    }

    @Test
    fun `a pipe-less row whose test cell was emptied is malformed`() {
        // Both outer pipes and the test name gone: the line is still inside the
        // table, so it is a row, and a row without a test is the defect the
        // malformed check exists for. Recognizing rows by their contents would
        // filter exactly this line out — the test is what it lost.
        val matrix =
            "| Row | Test |\n" +
                "|--|--|\n" +
                "| initial draft | `Owner.a test` |\n" +
                "| initial draft | \n" +
                "| another row | `Owner.another test` |\n"

        val parsed = BehaviorMatrixGate.parse(matrix)

        assertEquals(1, parsed.malformed.size, "a row that lost its test is a defect: ${parsed.malformed}")
        assertEquals(2, parsed.rows.size, "the rows around it are still rows: ${parsed.rows}")
    }

    @Test
    fun `prose after a table is not a row`() {
        // The table ends where the prose starts; a sentence with a pipe after
        // it must not become a malformed row.
        val matrix =
            "| Row | Test |\n" +
                "|--|--|\n" +
                "| a | `Owner.a test` |\n" +
                "\n" +
                "The expression is a | b, see `someHelper`.\n"

        val parsed = BehaviorMatrixGate.parse(matrix)

        assertEquals(1, parsed.rows.size, "prose after the table is not a row: ${parsed.rows}")
        assertTrue(parsed.malformed.isEmpty(), "prose is not a malformed row: ${parsed.malformed}")
    }

    @Test
    fun `a second table of the same source is read as its own table`() {
        val matrix =
            "| Row | Test |\n" +
                "|--|--|\n" +
                "| a | `Owner.a test` |\n" +
                "\n" +
                "Prose between the tables.\n" +
                "\n" +
                "| Row | Test |\n" +
                "|--|--|\n" +
                "| b | `Owner.b test` |\n"

        val parsed = BehaviorMatrixGate.parse(matrix)

        assertEquals(
            listOf("a test", "b test"),
            parsed.rows.map { it.testName },
            "both tables are read, and the prose between them is not: ${parsed.rows}",
        )
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

    @Test
    fun `a class name that two packages share is ambiguous`() {
        val bodies =
            mapOf(
                "Owner" to listOf("class Owner { }", "class Owner { }"),
                "Unique" to listOf("class Unique { }"),
            )

        val ambiguous = BehaviorMatrixGate.ambiguousClassNames(bodies)

        assertEquals(
            setOf("Owner"),
            ambiguous,
            "only the name that two classes claim is ambiguous: $ambiguous",
        )
    }

    @Test
    fun `a row that names an ambiguous class is not satisfied by either of them`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val bodies =
            mapOf(
                "Owner" to
                    listOf(
                        "\n    @Test\n    fun `a test`() {}\n",
                        "\n",
                    ),
            )

        val missing =
            BehaviorMatrixGate.missingRows(
                rows = BehaviorMatrixGate.parse(matrix).rows,
                matrixClassBody = null,
                ownerBody = { BehaviorMatrixGate.ownerBody(bodies, it) },
            )

        assertEquals(
            1,
            missing.size,
            "an ambiguous class name resolves to nothing, not to the first candidate: $missing",
        )
    }

    @Test
    fun `two classes of one file that share a name are ambiguous`() {
        val source =
            "class First {\n" +
                "    class Owner {\n" +
                "        @Test\n" +
                "        fun `a test`() {}\n" +
                "    }\n" +
                "}\n" +
                "class Second {\n" +
                "    class Owner {\n" +
                "    }\n" +
                "}\n"

        val bodies = BehaviorMatrixGate.classBodies(source)

        assertEquals(
            setOf("Owner"),
            BehaviorMatrixGate.ambiguousClassNames(bodies),
            "a name two classes of the same file claim is ambiguous: $bodies",
        )
    }

    @Test
    fun `a fully qualified test annotation is a test annotation`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner {\n" +
                "    @org.junit.jupiter.api.Test\n" +
                "    fun `a test`() {}\n" +
                "}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertTrue(missing.isEmpty(), "a fully qualified annotation is an annotation: $missing")
    }

    @Test
    fun `a class without a body claims its name too`() {
        val source =
            "class Owner\n" +
                "class Owner {\n" +
                "    @Test\n" +
                "    fun `a test`() {}\n" +
                "}\n"

        val bodies = BehaviorMatrixGate.classBodies(source)

        assertEquals(
            setOf("Owner"),
            BehaviorMatrixGate.ambiguousClassNames(bodies),
            "a class without a body claims the name as well: $bodies",
        )
    }

    @Test
    fun `a class without a body does not swallow the function that follows it`() {
        val source =
            "class Owner\n" +
                "fun helper() {\n" +
                "    @Test\n" +
                "    fun `a test`() {}\n" +
                "}\n"

        val bodies = BehaviorMatrixGate.classBodies(source)

        assertEquals(
            listOf(""),
            bodies["Owner"],
            "the body of the function that follows a bodyless class is not its body: $bodies",
        )
    }

    @Test
    fun `a class without a body does not swallow the object that follows it`() {
        val source =
            "class Owner\n" +
                "object Helpers {\n" +
                "    @Test\n" +
                "    fun `a test`() {}\n" +
                "}\n"

        val bodies = BehaviorMatrixGate.classBodies(source)

        assertEquals(
            listOf(""),
            bodies["Owner"],
            "the body of the object that follows a bodyless class is not its body: $bodies",
        )
    }

    @Test
    fun `a class without a body does not swallow the interface that follows it`() {
        val source =
            "class Owner\n" +
                "interface Helpers {\n" +
                "    @Test\n" +
                "    fun `a test`() {}\n" +
                "}\n"

        val bodies = BehaviorMatrixGate.classBodies(source)

        assertEquals(
            listOf(""),
            bodies["Owner"],
            "the body of the interface that follows a bodyless class is not its body: $bodies",
        )
    }

    @Test
    fun `a row whose test lives in an object that follows a bodyless class is missing`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner\n" +
                "object Helpers {\n" +
                "    @Test\n" +
                "    fun `a test`() {}\n" +
                "}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertEquals(
            1,
            missing.size,
            "a test of an object that follows a bodyless class does not cover the class: $missing",
        )
    }

    @Test
    fun `a supertype whose name ends in a declaration keyword does not end the header`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner : Myinterface {\n" +
                "    @Test\n" +
                "    fun `a test`() {}\n" +
                "}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertTrue(
            missing.isEmpty(),
            "a supertype that merely ends in a keyword is still a supertype: $missing",
        )
    }

    @Test
    fun `a row is not satisfied by a test of a class nested in the owner`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner {\n" +
                "    class Nested {\n" +
                "        @Test\n" +
                "        fun `a test`() {}\n" +
                "    }\n" +
                "}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertEquals(
            1,
            missing.size,
            "a test of a nested class belongs to that class, not to its owner: $missing",
        )
    }

    @Test
    fun `a row is not satisfied by a test of an object nested in the owner`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner {\n" +
                "    companion object {\n" +
                "        @Test\n" +
                "        fun `a test`() {}\n" +
                "    }\n" +
                "}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertEquals(
            1,
            missing.size,
            "a test of a companion object belongs to it, not to its owner: $missing",
        )
    }

    @Test
    fun `a row is satisfied by a test declared beside a nested class`() {
        val matrix = matrix("| a | `Owner.a test` |")
        val owner =
            "class Owner {\n" +
                "    class Nested {\n" +
                "        fun helper() {}\n" +
                "    }\n" +
                "    @Test\n" +
                "    fun `a test`() = runTest {\n" +
                "        val block = { 1 }\n" +
                "    }\n" +
                "}\n"

        val missing = missing(matrix, matrixClassBody = null, owner = owner)

        assertTrue(
            missing.isEmpty(),
            "a direct member is a member even when a nested class and a lambda block share the " +
                "body: $missing",
        )
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
            ownerBody = { bodies[it]?.singleOrNull() },
        )
    }

    /** The body of the class [className] of [source]. */
    private fun body(
        source: String,
        className: String,
    ): String? = BehaviorMatrixGate.classBodies(source)[className]?.singleOrNull()

    /** The source of a class that declares `a test`, with or without a test annotation. */
    private fun owner(annotated: Boolean): String {
        val annotation = if (annotated) "    @Test\n" else ""
        return "class Owner {\n$annotation    fun `a test`() {}\n}\n"
    }

    /** A matrix source with one row. */
    private fun matrix(row: String): String = "| Row | Test |\n|--|--|\n$row\n"
}
