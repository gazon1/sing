package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The instrument behind "should the catalog grow?".
 *
 * Seventeen component kinds were a guess with a prompt attached. These are the assertions that
 * make the next guess better than the last: the tally counts what a model *draws*, and it counts a
 * rejected component as a mistake rather than as use.
 */
@Tag("fast")
class GenuiUsageCounterTest {

    private val counter = GenuiUsageCounter()

    @Test
    fun `nothing is recorded before anything is drawn`() {
        assertTrue(counter.counts.value.isEmpty())
        assertTrue(counter.ranking().isEmpty())
    }

    @Test
    fun `each draw counts once`() {
        counter.record("task_card")
        counter.record("task_card")
        counter.record("heading")

        assertEquals(2, counter.counts.value.getValue("task_card"))
        assertEquals(1, counter.counts.value.getValue("heading"))
    }

    /**
     * The ranking is the report.
     *
     * "It never uses the card component" and "it uses it a third as often as text" are different
     * conclusions with different remedies, and a bare count cannot tell them apart.
     */
    @Test
    fun `the ranking puts the most used first`() {
        counter.record("heading")
        counter.record("task_card")
        counter.record("task_card")

        assertEquals(listOf("task_card" to 2, "heading" to 1), counter.ranking())
    }

    /**
     * The list that matters: a kind the catalog declares and nothing ever draws.
     *
     * Each entry is a question rather than a verdict — the prompt failed to mention the component,
     * or the component was never needed — and this is the only place either becomes visible.
     */
    @Test
    fun `a kind nothing draws is reported against the catalog that declares it`() {
        counter.record("heading")

        val unused = counter.neverUsed(SingularityCatalog)
        assertTrue(unused.contains("task_card"), "task_card was never drawn here: $unused")
        assertTrue(!unused.contains("heading"), "heading was drawn: $unused")
    }

    @Test
    fun `before anything is drawn, the whole catalog is unused`() {
        assertEquals(
            SingularityCatalog.components.keys.sorted(),
            counter.neverUsed(SingularityCatalog),
        )
    }

    @Test
    fun `reset forgets everything`() {
        counter.record("heading")
        counter.reset()
        assertTrue(counter.counts.value.isEmpty())
    }

    /**
     * A component the model wrote and the validator rejected is a mistake, not a use.
     *
     * This is the whole reason the tally is recorded in the factory rather than where the model's
     * text arrives. Counting it as usage would make a badly-documented component look popular —
     * the model keeps writing `chart`, the prompt keeps having to correct it, and the tally says
     * `chart` is in use. The instrument would then argue for building more of the thing that is
     * already failing.
     */
    @Test
    fun `a component the validator rejects does not count as used`() {
        val usage = GenuiUsageCounter()
        val factory = A2uiNodeFactory(SingularityCatalog, usage)

        val components: JsonArray = buildJsonArray {
            add(componentJson(id = "ok", kind = "heading", text = "Today"))
            add(componentJson(id = "bad", kind = "heading"))
        }
        factory.parseComponents(
            components,
            SurfaceId("s1"),
            "/createSurface/components",
        )

        // One, not two: the heading with no `text` is refused before the counter is reached.
        assertEquals(
            1,
            factory.usageCounter().counts.value.getValue("heading"),
            "Only the component that became a node counts as used",
        )
    }

    private fun componentJson(id: String, kind: String, text: String? = null): JsonObject {
        val body: String = if (text == null) "" else ""","text": "$text""""
        return Json.parseToJsonElement(
            """{"id":"$id","kind":"$kind"$body}""",
        ) as JsonObject
    }
}
