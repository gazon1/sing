package com.singularity.todo.core.ui.onboarding

import androidx.compose.ui.geometry.Rect
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When the tour starts, what it skips, and when it refuses to start.
 *
 * The machine is the whole decision surface for REQ-1…REQ-3 of the onboarding spec. It is
 * tested without a composition because the interesting cases — a target that never
 * appears, a replay from settings, a step the user has already finished — are all
 * ordinary state, and a UI test would only be able to reach them by arranging pixels.
 */
@Tag("fast")
class SpotlightTourStateMachineTest {

    private fun bounds(): Rect = Rect(left = 10f, top = 10f, right = 90f, bottom = 90f)

    private fun steps() = SpotlightContent.STEPS

    @Test
    fun `the tour does not start for a user who has already seen this version`() {
        val machine = SpotlightTourStateMachine(steps())

        machine.startIfEligible(
            alreadySeen = true,
            force = false,
            anchors = mapOf(SpotlightAnchorId.SavedViews to bounds()),
        )

        assertNull(machine.index, "a version the user has finished must not replay itself")
    }

    @Test
    fun `the tour does not start before any target has been measured`() {
        val machine = SpotlightTourStateMachine(steps())

        machine.startIfEligible(alreadySeen = false, force = false, anchors = emptyMap())

        assertNull(machine.index, "there is nothing to point at yet")
        assertEquals(true, machine.ready, "the screen is allowed to draw; it will draw nothing")
    }

    @Test
    fun `the tour starts on the first step once a target is measured`() {
        val machine = SpotlightTourStateMachine(steps())

        machine.startIfEligible(
            alreadySeen = false,
            force = false,
            anchors = mapOf(SpotlightAnchorId.SavedViews to bounds()),
        )

        assertEquals(0, machine.index)
    }

    @Test
    fun `asking for the tour again starts it even though it has been seen`() {
        val machine = SpotlightTourStateMachine(steps())

        machine.startIfEligible(
            alreadySeen = true,
            force = true,
            anchors = mapOf(SpotlightAnchorId.SavedViews to bounds()),
        )

        assertEquals(0, machine.index, "the settings action must work for a user who has seen it")
    }

    @Test
    fun `a step whose target is not on screen is skipped rather than waited on`() {
        val machine = SpotlightTourStateMachine(steps())

        // Only the second step's anchor is present.
        machine.startIfEligible(
            alreadySeen = false,
            force = false,
            anchors = mapOf(SpotlightAnchorId.SaveCurrent to bounds()),
        )

        assertEquals(
            1,
            machine.index,
            "the first step points at something that is not laid out and must be skipped",
        )
    }

    @Test
    fun `advancing past the last playable step ends the tour`() {
        val machine = SpotlightTourStateMachine(steps())
        machine.startIfEligible(
            alreadySeen = false,
            force = false,
            anchors = mapOf(
                SpotlightAnchorId.SavedViews to bounds(),
                SpotlightAnchorId.SaveCurrent to bounds(),
            ),
        )

        machine.advance(
            mapOf(
                SpotlightAnchorId.SavedViews to bounds(),
                SpotlightAnchorId.SaveCurrent to bounds(),
            ),
        )
        assertEquals(1, machine.index)

        machine.advance(
            mapOf(
                SpotlightAnchorId.SavedViews to bounds(),
                SpotlightAnchorId.SaveCurrent to bounds(),
            ),
        )
        assertNull(machine.index, "there is no step after the last one; the tour is over")
    }

    @Test
    fun `finishing early stops the tour`() {
        val machine = SpotlightTourStateMachine(steps())
        machine.startIfEligible(
            alreadySeen = false,
            force = false,
            anchors = mapOf(SpotlightAnchorId.SavedViews to bounds()),
        )

        machine.finish()

        assertNull(machine.index, "skipping must actually end it, not hide it until the next launch")
    }

    @Test
    fun `advancing while not running changes nothing`() {
        val machine = SpotlightTourStateMachine(steps())

        machine.advance(mapOf(SpotlightAnchorId.SavedViews to bounds()))

        assertNull(machine.index)
    }

    @Test
    fun `every shipped step points at an anchor the app actually registers`() {
        // A step whose anchor nothing registers is skipped forever, so it would be a
        // step the user can never see and nobody would notice.
        val known = setOf(SpotlightAnchorId.SavedViews, SpotlightAnchorId.SaveCurrent)

        assertEquals(
            emptyList(),
            SpotlightContent.STEPS.filter { it.anchor !in known },
            "every step must point at a known anchor",
        )
    }

    @Test
    fun `every shipped step has its own id and non-empty copy`() {
        val ids = SpotlightContent.STEPS.map { it.id }

        assertEquals(ids.size, ids.toSet().size, "step ids must be unique; the index is the identity")
        SpotlightContent.STEPS.forEach {
            assertEquals(true, it.title.isNotBlank(), "step ${it.id} has no title")
            assertEquals(true, it.body.isNotBlank(), "step ${it.id} has no body")
        }
    }
}
