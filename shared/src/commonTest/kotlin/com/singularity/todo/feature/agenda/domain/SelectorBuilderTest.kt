package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.selector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class SelectorBuilderTest {

    // ─── allOf ────────────────────────────────────────────────────────────────

    @Test
    fun `allOf with vararg`() {
        val s = selector {
            allOf(Selector.DateBucket(RelativeBucket.Today), Selector.Pinned)
        }
        val allOf = s as Selector.AllOf
        assertEquals(2, allOf.children.size)
        assertIs<Selector.DateBucket>(allOf.children[0])
        assertIs<Selector.Pinned>(allOf.children[1])
    }

    @Test
    fun `allOf with List`() {
        val children = listOf(Selector.DateBucket(RelativeBucket.Today), Selector.Pinned)
        val s = selector { allOf(children) }
        val allOf = s as Selector.AllOf
        assertEquals(2, allOf.children.size)
    }

    // ─── anyOf ───────────────────────────────────────────────────────────────

    @Test
    fun `anyOf with vararg`() {
        val s = selector {
            anyOf(Selector.Pinned, Selector.DateBucket(RelativeBucket.Overdue))
        }
        val anyOf = s as Selector.AnyOf
        assertEquals(2, anyOf.children.size)
    }

    @Test
    fun `anyOf with List`() {
        val s = selector {
            anyOf(listOf(Selector.Pinned, Selector.DateBucket(RelativeBucket.Overdue)))
        }
        assertIs<Selector.AnyOf>(s)
    }

    // ─── not ────────────────────────────────────────────────────────────────

    @Test
    fun `not wraps child`() {
        val s = selector { not(Selector.Completed) }
        val not = s as Selector.Not
        assertEquals(Selector.Completed, not.child)
    }

    // ─── empty block → error ───────────────────────────────────────────────

    @Test
    fun `empty block throws`() {
        assertFailsWith<IllegalStateException> {
            selector { }
        }
    }

    // ─── single child — no AllOf wrapper ───────────────────────────────────

    @Test
    fun `single allOf child returns AllOf`() {
        val child = Selector.DateBucket(RelativeBucket.Today)
        val s = selector { allOf(child) }
        assertIs<Selector.AllOf>(s)
        assertEquals(1, s.children.size)
        assertSame(child, s.children[0])
    }

    @Test
    fun `single anyOf child returns AnyOf`() {
        val child = Selector.Pinned
        val s = selector { anyOf(child) }
        assertIs<Selector.AnyOf>(s)
        assertEquals(1, s.children.size)
        assertSame(child, s.children[0])
    }

    // ─── nested composition ─────────────────────────────────────────────────

    @Test
    fun `nested composition works`() {
        val result = selector {
            allOf(
                Selector.Tags(setOf(), matchAll = false),
            )
            not(Selector.Completed)
        }
        // build() returns AllOf(children) for size>1
        assertIs<Selector.AllOf>(result)
        assertEquals(2, result.children.size)
        assertIs<Selector.AllOf>(result.children[0])
        assertIs<Selector.Not>(result.children[1])
    }
}
