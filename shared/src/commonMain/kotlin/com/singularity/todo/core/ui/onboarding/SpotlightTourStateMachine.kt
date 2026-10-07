package com.singularity.todo.core.ui.onboarding

import androidx.compose.ui.geometry.Rect

/**
 * Pure state machine behind the spotlight tour.
 *
 * All of the decisions live here rather than in the composable so they can be tested
 * without a composition: which step is showing, what happens when a step's target is not
 * on screen, and when the tour is allowed to start at all.
 *
 * Two rules shape it:
 *
 * - **A step with no laid-out target is skipped, never waited on.** A tour that blocks
 *   because one button is missing leaves a scrim over the app with no way out, which is
 *   worse than a tour one step shorter.
 * - **The tour starts only once at least one target is measured.** Starting on the first
 *   frame would aim the hole at a rectangle that does not exist yet.
 */
class SpotlightTourStateMachine(private val steps: List<SpotlightStep>) {

    /** Index of the step being shown, or null when the tour is not running. */
    var index: Int? = null
        private set

    /** True once at least one target has been measured and the scrim may be drawn. */
    var ready: Boolean = false
        private set

    /**
     * Decides whether to start, given what has been seen and what is laid out.
     *
     * [alreadySeen] is the user's stored answer; [force] is the settings action asking
     * for the tour again. Force does not clear the stored value — the value is a record
     * of what the user has been shown, and re-showing them something they have seen
     * does not un-see it.
     */
    fun startIfEligible(alreadySeen: Boolean, force: Boolean, anchors: Map<SpotlightAnchorId, Rect?>) {
        ready = true
        if (alreadySeen && !force) return
        index = nextPlayableFrom(0, anchors)
    }

    /** Advances to the next step that has a target, or finishes if there is none. */
    fun advance(anchors: Map<SpotlightAnchorId, Rect?>) {
        val current = index ?: return
        index = nextPlayableFrom(current + 1, anchors)
    }

    /** Ends the tour early, recording it as seen. */
    fun finish() {
        index = null
        ready = true
    }

    private fun nextPlayableFrom(from: Int, anchors: Map<SpotlightAnchorId, Rect?>): Int? {
        for (i in from until steps.size) {
            if (anchors[steps[i].anchor] != null) return i
        }
        return null
    }

    companion object {
        /** Convenience for the map the machine reads: present anchors only. */
        fun anchorsOf(vararg pairs: Pair<SpotlightAnchorId, Rect?>): Map<SpotlightAnchorId, Rect?> =
            pairs.toMap()
    }
}
