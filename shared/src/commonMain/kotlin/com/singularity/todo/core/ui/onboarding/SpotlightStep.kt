package com.singularity.todo.core.ui.onboarding

/**
 * One highlightable element of the interface.
 *
 * @property id Stable identity, used as the key for the anchor registry and for
 *              deciding which step the tour is on. Must not change when the copy
 *              changes — [SpotlightContent.VERSION] is what tracks content.
 * @property title One short line. Headline only; the body carries the explanation.
 * @property body One or two sentences saying what the element does and when to use it.
 * @property anchor Which laid-out element is highlighted. A step whose anchor never
 *                 registers is skipped rather than blocking the tour forever.
 * @property shape How the hole is cut. A round icon button wants a circle; a wide row
 *                 wants a rounded rectangle.
 */
data class SpotlightStep(
    val id: String,
    val title: String,
    val body: String,
    val anchor: SpotlightAnchorId,
    val shape: SpotlightShape = SpotlightShape.Rounded(),
)

/** How the scrim's hole is shaped around the target. */
sealed interface SpotlightShape {
    /** A circle inscribed in the target's bounds. Use for icon buttons and FABs. */
    data object Circle : SpotlightShape

    /**
     * A rounded rectangle. [radiusFraction] is of the *smaller* side, so the same
     * value reads as a pill on a wide row and as a soft square on a small one, instead
     * of needing two separate steps for the same element at different sizes.
     */
    data class Rounded(val radiusFraction: Float = 0.25f) : SpotlightShape
}

/**
 * Identity of a laid-out element that a [SpotlightStep] can point at.
 *
 * The tour points at elements by identity rather than by screen, because the same
 * affordance is reachable from more than one place and a step that named a screen would
 * silently stop working the moment it moved.
 */
@JvmInline
value class SpotlightAnchorId(val value: String) {
    init {
        require(value.isNotBlank()) { "anchor id must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** Agenda top bar: open the list of saved, reusable agenda views. */
        val SavedViews = SpotlightAnchorId("agenda.saved_views")

        /** Agenda top bar: save the current agenda view for later. */
        val SaveCurrent = SpotlightAnchorId("agenda.save_current")
    }
}

/**
 * The tour's content, and the rule for when it is shown again.
 *
 * [VERSION] is the whole "once per version of the content" mechanism: the stored value
 * is the highest version the user has finished, and any bump re-shows the tour to
 * everyone. Storing a *list* of seen step ids instead would mean a step added in v2
 * triggers a full re-run of v1's steps too, which is not what "the content changed"
 * means to the person looking at it.
 */
object SpotlightContent {

    /** Bump whenever [STEPS] changes in a way worth showing again. */
    const val VERSION: Int = 1

    /**
     * The tour.
     *
     * Both steps point at top-bar actions on the agenda screen, because that is where
     * the features are genuinely undiscoverable: there is no label, no menu, and no
     * onboarding anywhere else that mentions them. Pointing the tour at something
     * obvious instead would spend the one moment the user is most willing to listen.
     */
    val STEPS: List<SpotlightStep> = listOf(
        SpotlightStep(
            id = "agenda.saved_views.intro",
            title = "Saved views",
            body = "Views you have saved show up here. Open one to jump straight to it.",
            anchor = SpotlightAnchorId.SavedViews,
            shape = SpotlightShape.Circle,
        ),
        SpotlightStep(
            id = "agenda.save_current.intro",
            title = "Save this view",
            body = "Saves the filters and sections you have set up, so you can return to exactly this.",
            anchor = SpotlightAnchorId.SaveCurrent,
            shape = SpotlightShape.Circle,
        ),
    )
}
