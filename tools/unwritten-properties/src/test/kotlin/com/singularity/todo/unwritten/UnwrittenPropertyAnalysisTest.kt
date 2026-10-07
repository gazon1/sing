package com.singularity.todo.unwritten

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the read-but-never-written analysis.
 *
 * Every test here is a shape that produced a false positive in an earlier version of
 * this check. The regex prototype returned 63 candidates whose first entry was healthy
 * code, and the rule-shaped attempt reported a healthy `isLoading` as unwritten because
 * it could not see the write in another file. A gate that cries wolf gets switched off,
 * so "does not report" matters at least as much as "reports".
 */
class UnwrittenPropertyAnalysisTest {

    private val owner = "com.singularity.todo.feature.auth.LoginUiState"

    private fun declaration(
        name: String,
        owner: String = this.owner,
        ctor: Boolean = true,
    ) = Declaration(owner = owner, name = name, isConstructorParameter = ctor)

    private fun read(name: String, owner: String = this.owner) =
        Reference(owner = owner, name = name, isWrite = false)

    private fun write(name: String, owner: String = this.owner) =
        Reference(owner = owner, name = name, isWrite = true)

    // ── the defect ────────────────────────────────────────────────────────────

    @Test
    fun `a read with no write is reported`() {
        val findings = findNeverWritten(
            declarations = listOf(declaration("isLoading")),
            references = listOf(read("isLoading")),
        )
        assertEquals(1, findings.size)
        assertEquals("isLoading", findings.single().name)
    }

    @Test
    fun `the reported property is named and located`() {
        val finding = findNeverWritten(
            listOf(declaration("isLoading")),
            listOf(read("isLoading")),
        ).single()
        assertEquals(owner, finding.owner)
        assertTrue(
            finding.explanation.contains("never"),
            "the explanation has to say what is wrong, not just that something is",
        )
    }

    // ── the false positives that killed the first two attempts ────────────────

    @Test
    fun `a property written by copy is not reported`() {
        // The shape the regex prototype got wrong: the write is a named argument to
        // `copy`, not anything spelled like an assignment. Every state update in this
        // codebase is written this way, so a scan that misses it reports most of the
        // healthy state classes as broken.
        val findings = findNeverWritten(
            declarations = listOf(declaration("isLoading")),
            references = listOf(read("isLoading"), write("isLoading")),
        )
        assertTrue(findings.isEmpty(), "a copy-assigned property is written: $findings")
    }

    @Test
    fun `a property written once is not reported`() {
        val findings = findNeverWritten(
            declarations = listOf(declaration("isLoading")),
            references = listOf(read("isLoading"), write("isLoading"), write("isLoading")),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a write in one file suppresses a read in another`() {
        // The reason this is a KSP processor and not a detekt rule: the read and the
        // write are different files, and a per-file scan cannot see the write.
        val findings = findNeverWritten(
            declarations = listOf(declaration("isLoading")),
            references = listOf(read("isLoading"), write("isLoading")),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a property never read is not reported`() {
        // A different smell with a different fix. Reporting it here would put two
        // defects in one gate.
        val findings = findNeverWritten(
            declarations = listOf(declaration("orphan")),
            references = listOf(write("orphan")),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a property neither read nor written is not reported`() {
        assertTrue(findNeverWritten(listOf(declaration("dead")), emptyList()).isEmpty())
    }

    // ── scoping ───────────────────────────────────────────────────────────────

    @Test
    fun `a non-state class is out of scope`() {
        // Repository and domain classes hold plenty of vals written by a framework.
        val findings = findNeverWritten(
            declarations = listOf(declaration("cache", owner = "com.singularity.todo.TaskRepository")),
            references = listOf(read("cache", owner = "com.singularity.todo.TaskRepository")),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a Room DAO is exempt`() {
        val daoOwner = "com.singularity.todo.TaskDao"
        val findings = findNeverWritten(
            declarations = listOf(declaration("pending", owner = daoOwner)),
            references = listOf(read("pending", owner = daoOwner)),
        )
        assertTrue(findings.isEmpty(), "Room writes DAO properties itself")
    }

    @Test
    fun `the same name on two owners is judged separately`() {
        // A name is not an identity. Matching on `name` alone is how a healthy class
        // gets implicated by another class's defect.
        val other = "com.singularity.todo.feature.other.SearchUiState"
        val findings = findNeverWritten(
            declarations = listOf(
                declaration("query"),
                declaration("query", owner = other),
            ),
            references = listOf(
                read("query"),
                read("query", owner = other),
                write("query", owner = other),
            ),
        )
        assertEquals(1, findings.size, "only the unwritten one is a finding: $findings")
        assertEquals(owner, findings.single().owner)
    }

    // ── output stability ──────────────────────────────────────────────────────

    @Test
    fun `findings come back in a stable order`() {
        // A gate that reshuffles its findings between runs cannot be diffed, and a
        // baseline nobody can read is a baseline nobody maintains.
        val second = "com.singularity.todo.feature.bbb.SecondUiState"
        val third = "com.singularity.todo.feature.aaa.FirstUiState"
        val declarations = listOf(
            declaration("zeta", owner = second),
            declaration("alpha", owner = third),
            declaration("mid", owner = second),
        )
        val references = listOf(
            read("zeta", owner = second),
            read("alpha", owner = third),
            read("mid", owner = second),
        )
        val once = findNeverWritten(declarations, references)
        val twice = findNeverWritten(declarations.reversed(), references.reversed())
        assertEquals(once.map { it.owner to it.name }, twice.map { it.owner to it.name })
        assertEquals(
            listOf(third to "alpha", second to "mid", second to "zeta"),
            once.map { it.owner to it.name },
            "sorted by owner then name",
        )
    }

    @Test
    fun `a constructor property and a body property read the same`() {
        // The two declaration shapes a state class uses; both are in scope.
        val findings = findNeverWritten(
            declarations = listOf(
                declaration("fromCtor", ctor = true),
                declaration("fromBody", ctor = false),
            ),
            references = listOf(read("fromCtor"), read("fromBody")),
        )
        assertEquals(2, findings.size)
    }

    @Test
    fun `owner matching ignores the package`() {
        assertTrue(isStateOwner("com.singularity.todo.FooUiState"))
        assertTrue(isStateOwner("com.singularity.todo.BarState"))
        assertTrue(!isStateOwner("com.singularity.todo.Stateship"))
    }
}