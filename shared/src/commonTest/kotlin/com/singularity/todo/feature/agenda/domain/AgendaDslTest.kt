package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.feature.agenda.domain.model.AgendaLayout
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.agenda
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Tag("fast")
class AgendaDslTest {

    @Test
    fun `agenda DSL builds AgendaDefinition`() {
        val def = agenda("My Agenda", AgendaLayout.ListFlat) {
            section("Today") {
                selector = Selector.DateBucket(RelativeBucket.Today)
            }
            section("Overdue", discard = true) {
                selector = Selector.DateBucket(RelativeBucket.Overdue)
            }
        }

        assertEquals("My Agenda", def.title)
        assertEquals(AgendaLayout.ListFlat, def.layout)
        assertEquals(2, def.sections.size)

        assertEquals("Today", def.sections[0].name)
        assertEquals(RelativeBucket.Today, (def.sections[0].selector as Selector.DateBucket).bucket)

        assertEquals("Overdue", def.sections[1].name)
        assertEquals(true, def.sections[1].discard)
    }

    @Test
    fun `section order defaults to size order`() {
        val def = agenda("Test") {
            section("First") {
                selector = Selector.DateBucket(RelativeBucket.Today)
            }
            section("Second") {
                selector = Selector.DateBucket(RelativeBucket.Tomorrow)
            }
        }
        assertEquals(0, def.sections[0].order)
        assertEquals(1, def.sections[1].order)
    }

    @Test
    fun `section order can be overridden`() {
        val def = agenda("Test") {
            section("Second", order = 1) {
                selector = Selector.DateBucket(RelativeBucket.Today)
            }
            section("First", order = 0) {
                selector = Selector.DateBucket(RelativeBucket.Tomorrow)
            }
        }
        // Sections are stored in declaration order; sorting by 'order' happens
        // in AgendaEvaluator.evaluate(), not at AgendaDefinition construction time.
        assertEquals("Second", def.sections[0].name)
        assertEquals(1, def.sections[0].order)
        assertEquals("First", def.sections[1].name)
        assertEquals(0, def.sections[1].order)
    }

    @Test
    fun `AllOf and AnyOf combinators work in DSL`() {
        val def = agenda("Test") {
            section("Complex") {
                selector = Selector.AllOf(
                    listOf(
                        Selector.DateBucket(RelativeBucket.Today),
                        Selector.Pinned,
                    ),
                )
            }
            section("Any Of") {
                selector = Selector.AnyOf(
                    listOf(
                        Selector.Pinned,
                        Selector.DateBucket(RelativeBucket.Overdue),
                    ),
                )
            }
        }

        assertEquals(2, def.sections.size)
        val allOf = def.sections[0].selector as Selector.AllOf
        assertEquals(2, allOf.children.size)
        val anyOf = def.sections[1].selector as Selector.AnyOf
        assertEquals(2, anyOf.children.size)
    }

    @Test
    fun `Not combinator works in DSL`() {
        val def = agenda("Test") {
            section("Not Completed") {
                selector = Selector.Not(Selector.Completed)
            }
        }
        val section = def.sections[0].selector as Selector.Not
        assertEquals(Selector.Completed, section.child)
    }

    // ─── Baseline: current contract before MR1 strictens it ───────────────────

    /**
     * Empty title is currently accepted (MR1 will NOT add validation for title —
     * title is user-facing metadata, not a structural constraint).
     */
    @Test
    fun `empty title is accepted`() {
        val def = agenda("") {
            section("Today") {
                selector = Selector.DateBucket(RelativeBucket.Today)
            }
        }
        assertEquals("", def.title)
    }

    /**
     * Section without selector in block and without selector parameter
     * throws IllegalStateException from checkNotNull with a clear message.
     * MR1 replaced the UninitializedPropertyAccessException with an explicit check.
     */
    @Test
    fun `section without selector throws`() {
        assertFailsWith<IllegalStateException> {
            agenda("Test") {
                section("X") {
                    // selector not assigned
                }
            }
        }
    }

    /**
     * Assigning selector via property in SectionScope works.
     * This is the primary DSL usage pattern alongside the parameter form.
     */
    @Test
    fun `section selector assignment in block works`() {
        val def = agenda("Test") {
            section("Today") {
                this.selector = Selector.DateBucket(RelativeBucket.Today)
            }
        }
        assertEquals(RelativeBucket.Today, (def.sections[0].selector as Selector.DateBucket).bucket)
    }
}
