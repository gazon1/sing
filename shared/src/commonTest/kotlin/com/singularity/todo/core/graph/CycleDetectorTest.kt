package com.singularity.todo.core.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CycleDetectorTest {

    // ─── Self-loop ──────────────────────────────────────────────────────────────

    @Test
    fun `self-loop returns SelfLoop`() {
        val result = CycleDetector.detect(
            node = "A",
            newParent = "A",
            edges = { emptyList() },
        )
        assertIs<CycleError.SelfLoop>(result.exceptionOrNull())
        assertEquals("A", (result.exceptionOrNull() as CycleError.SelfLoop).node)
    }

    // ─── Linear chains ────────────────────────────────────────────────────────

    @Test
    fun `A depends B, B depends C, adding C depends A is a cycle`() {
        // graph: A → B → C  (A depends on B, B depends on C)
        // adding C → A creates A → B → C → A
        val edges = mapOf(
            "A" to listOf("B"),
            "B" to listOf("C"),
            "C" to listOf(),
        )

        val result = CycleDetector.detect(
            node = "C",
            newParent = "A",
            edges = { edges[it] ?: emptyList() },
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertIs<CycleError.Cycle>(error)
        // path must contain A (the entry of the cycle) and be non-empty
        assertTrue(error.path.isNotEmpty())
        assertTrue(error.path.contains("A"))
    }

    @Test
    fun `safe dependency in a chain returns success`() {
        // graph: A → B → C
        // adding D → A is safe (D does not affect A's upstream)
        val edges = mapOf(
            "A" to listOf("B"),
            "B" to listOf("C"),
            "C" to listOf(),
        )

        val result = CycleDetector.detect(
            node = "D",
            newParent = "A",
            edges = { edges[it] ?: emptyList() },
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `isolated node returns success`() {
        val edges = mapOf<String, List<String>>()

        val result = CycleDetector.detect(
            node = "X",
            newParent = "Y",
            edges = { edges[it] ?: emptyList() },
        )

        assertTrue(result.isSuccess)
    }

    // ─── Diamond / DAG ────────────────────────────────────────────────────────

    @Test
    fun `diamond graph A and D both depend on B and C, adding D depends B creates cycle`() {
        // D → {B, C} and B → {} — D already depends on B.
        // Adding B → D (B depends on D) creates mutual blocking: B blocks D, D blocks B.
        val edges = mapOf(
            "A" to listOf("B", "C"),
            "B" to listOf(),
            "C" to listOf(),
            "D" to listOf("B", "C"),
        )

        val result = CycleDetector.detect(
            node = "B",
            newParent = "D",
            edges = { edges[it] ?: emptyList() },
        )

        // D already depends on B; adding B depends D closes the cycle.
        assertTrue(result.isFailure)
        assertIs<CycleError.Cycle>(result.exceptionOrNull())
    }

    @Test
    fun `diamond graph A and D depend on B and C, adding D depends B is safe`() {
        // D → {B, C}, A → {B, C}, B → {}, C → {}.
        // detect(D, B): can B reach D through deps? B has no deps → no, so safe.
        val edges = mapOf(
            "A" to listOf("B", "C"),
            "B" to listOf(),
            "C" to listOf(),
            "D" to listOf("B", "C"),
        )

        val result = CycleDetector.detect(
            node = "D",
            newParent = "B",
            edges = { edges[it] ?: emptyList() },
        )

        // B has no deps; it cannot reach D. Adding D→B is safe.
        assertTrue(result.isSuccess)
    }

    @Test
    fun `diamond graph A and D depend on C only, adding D depends B is safe`() {
        // D → {C}, A → {C}, C → {}, B → {}.
        // detect(B, D): can D reach B through deps? D→C, C→{} → no, so safe.
        val edges = mapOf(
            "A" to listOf("C"),
            "B" to listOf(),
            "C" to listOf(),
            "D" to listOf("C"),
        )

        val result = CycleDetector.detect(
            node = "B",
            newParent = "D",
            edges = { edges[it] ?: emptyList() },
        )

        // D reaches C but not B. Adding B→D is safe.
        assertTrue(result.isSuccess)
    }

    // ─── Direct cycle ────────────────────────────────────────────────────────

    @Test
    fun `direct two-node cycle`() {
        // A → {B}, B → {} : A depends on B.
        // Adding B → A (B depends on A): A already reaches B, so cycle.
        val edges = mapOf(
            "A" to listOf("B"),
            "B" to listOf(),
        )

        // Check: can newParent(A) reach node(B)? A→B → yes, cycle.
        val result = CycleDetector.detect(
            node = "B",
            newParent = "A",
            edges = { edges[it] ?: emptyList() },
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertIs<CycleError.Cycle>(error)
    }

    // ─── Multi-level cycle ───────────────────────────────────────────────────

    @Test
    fun `three-node cycle`() {
        // A → {B}, B → {C}, C → {} : A depends on B, B depends on C.
        // Adding C → A (C depends on A): A reaches C transitively, so cycle.
        val edges = mapOf(
            "A" to listOf("B"),
            "B" to listOf("C"),
            "C" to listOf(),
        )

        // Check: can newParent(A) reach node(C)? A→B→C → yes, cycle.
        val result = CycleDetector.detect(
            node = "C",
            newParent = "A",
            edges = { edges[it] ?: emptyList() },
        )

        assertTrue(result.isFailure)
        assertIs<CycleError.Cycle>(result.exceptionOrNull())
    }

    // ─── Missing node ─────────────────────────────────────────────────────────

    @Test
    fun `non-existent edge target is safe`() {
        val edges = mapOf<String, List<String>>()

        val result = CycleDetector.detect(
            node = "A",
            newParent = "NONEXISTENT",
            edges = { edges[it] ?: emptyList() },
        )

        assertTrue(result.isSuccess)
    }

    // ─── Self-loop via newParent ──────────────────────────────────────────────

    @Test
    fun `setting node to depend on itself is SelfLoop`() {
        val edges = mapOf(
            "A" to listOf("B"),
            "B" to listOf(),
        )

        val result = CycleDetector.detect(
            node = "A",
            newParent = "A",
            edges = { edges[it] ?: emptyList() },
        )

        assertTrue(result.isFailure)
        assertIs<CycleError.SelfLoop>(result.exceptionOrNull())
    }

    // ─── Edge cases ──────────────────────────────────────────────────────────

    @Test
    fun `empty graph returns success`() {
        val result = CycleDetector.detect(
            node = "X",
            newParent = "Y",
            edges = { emptyList() },
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `node with no edges and different newParent returns success`() {
        val edges = mapOf(
            "A" to emptyList<String>(),
        )

        val result = CycleDetector.detect(
            node = "A",
            newParent = "B",
            edges = { edges[it] ?: emptyList() },
        )

        assertTrue(result.isSuccess)
    }
}
